package proxy

import cats.effect.IO
import cats.syntax.all.*
import com.comcast.ip4s.{Host as IpHost, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.Network
import io.circe.{Json, parser}
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

object Proxy:
  val HealthPath: String = "/healthz"

  private val LogBodyLimit = 256L * 1024
  private val BodyClip = 10000
  private val HealthTimeout = 1.second

  private val TokenHeader = ci"X-Proxy-Token"
  private val RequestIdHeader = ci"X-Request-Id"
  private val HostHeader = ci"Host"
  private val UserAgentHeader = ci"User-Agent"

  private val hopByHop: Set[CIString] = Set(
    ci"Connection",
    ci"Keep-Alive",
    ci"Proxy-Authenticate",
    ci"Proxy-Authorization",
    ci"TE",
    ci"Trailer",
    ci"Transfer-Encoding",
    ci"Upgrade"
  )

  def app(config: Config, client: Client[IO], log: JsonLog): HttpApp[IO] = HttpApp[IO]: request =>
    if request.method == Method.GET && path(request) == HealthPath then health(config, log, request)
    else handle(config, client, log, request)

  private def handle(config: Config, client: Client[IO], log: JsonLog, request: Request[IO]): IO[Response[IO]] =
    for
      ctx <- newContext(request)
      start <- IO.realTimeInstant
      begin <- IO.monotonic
      _ <- log.info(
        "app.http",
        "Received http request",
        Some(ctx),
        (List(
          "path" -> Json.fromString(path(request)),
          "uri" -> Json.fromString(fullUri(request)),
          "headers" -> headersJson(request.headers)
        ) ++ debugObject(config, request))*
      )
      response <-
        if authorized(config, request) then
          // ponytail: a body streamed unbuffered past LOG_BODY_MAX_BYTES can still fail after headers are already on the wire; unrecoverable by design
          forward(config, client, log, request, ctx)
            .handleErrorWith(failure => unreachable(config, log, ctx, request, failure))
        else IO.pure(errorResponse(Status.Unauthorized, "unauthorized"))
      end <- IO.monotonic
      _ <- operation(config, log, request, ctx, response.status, start, (end - begin).toMillis)
    yield
      if response.headers.get(RequestIdHeader).isDefined then response
      else response.putHeaders(Header.Raw(RequestIdHeader, ctx.requestId))

  private def forward(
      config: Config,
      client: Client[IO],
      log: JsonLog,
      request: Request[IO],
      ctx: ReqCtx
  ): IO[Response[IO]] =
    val outboundUri = config.targetUrl + path(request)
    for
      snapshot <- snapshotBody(request.headers, request.body, config.logBodyMaxBytes)
      (sent, sentBody) = snapshot
      _ <- log.info(
        "app.http",
        s"Method proxy was called for ${path(request)} with body '${sent.bodyType}'",
        Some(ctx),
        "templated_path" -> Json.fromString(path(request)),
        "uri" -> Json.fromString(path(request).stripPrefix("/")),
        "query" -> Json.fromString(request.uri.query.renderString),
        "raw_body" -> sent.rawBody,
        "body" -> Json.fromString(renderBody(sent, config.logBodies, config.logLevel))
      )
      outbound = Request[IO](
        method = request.method,
        uri = upstreamUri(config, request.uri),
        httpVersion = request.httpVersion,
        headers = forwardHeaders(config, request.headers, ctx.requestId)
      ).withBodyStream(sentBody)
      _ <- log.info(
        "app.outbound",
        "Sending http request",
        Some(ctx),
        "http_method" -> Json.fromString(request.method.name),
        "uri" -> Json.fromString(outboundUri),
        "headers" -> headersJson(outbound.headers),
        "body" -> Json.fromString(renderBody(sent, config.logBodies, config.logLevel))
      )
      began <- IO.monotonic
      attempt <- client.toHttpApp.run(outbound).attempt
      finished <- IO.monotonic
      duration = (finished - began).toMillis
      response <- attempt match
        case Right(upstream) => relay(config, log, ctx, request, outboundUri, duration, upstream)
        case Left(failure)   => reject(config, log, ctx, request, outboundUri, duration, failure)
    yield response

  private def relay(
      config: Config,
      log: JsonLog,
      ctx: ReqCtx,
      request: Request[IO],
      outboundUri: String,
      duration: Long,
      upstream: Response[IO]
  ): IO[Response[IO]] =
    for
      snapshot <- snapshotBody(upstream.headers, upstream.body, config.logBodyMaxBytes)
      (received, receivedBody) = snapshot
      body = Json.fromString(renderBody(received, config.logBodies, config.logLevel))
      _ <- log.info(
        "app.outbound",
        s"Got http response with code=${upstream.status.code}",
        Some(ctx),
        "http_method" -> Json.fromString(request.method.name),
        "uri" -> Json.fromString(outboundUri),
        "code" -> Json.fromInt(upstream.status.code),
        "duration" -> Json.fromLong(duration),
        "headers" -> headersJson(upstream.headers),
        "raw_body" -> received.rawBody,
        "body" -> body
      )
      _ <- log.info(
        "app.http",
        "Method proxy returned response",
        Some(ctx),
        (List(
          "code" -> Json.fromInt(upstream.status.code),
          "raw_body" -> received.rawBody,
          "body" -> body
        ) ++ debugObject(config, request, headerValue(upstream.headers, ci"Content-Type"), Some(received.length)))*
      )
    yield Response[IO](
      status = upstream.status,
      httpVersion = upstream.httpVersion,
      headers = responseHeaders(upstream.headers)
    ).withBodyStream(receivedBody)

  private def classify(failure: Throwable): (Status, String) =
    if failure.isInstanceOf[TimeoutException] then (Status.GatewayTimeout, "upstream timeout")
    else (Status.BadGateway, "upstream unreachable")

  // catches failures that escape forward/relay outright (buffering the inbound or upstream body), not just a failed connect
  private def unreachable(
      config: Config,
      log: JsonLog,
      ctx: ReqCtx,
      request: Request[IO],
      failure: Throwable
  ): IO[Response[IO]] =
    val (status, message) = classify(failure)
    log
      .error(
        "app.outbound",
        "Request handling failed",
        Some(ctx),
        "http_method" -> Json.fromString(request.method.name),
        "uri" -> Json.fromString(config.targetUrl + path(request)),
        "error" -> Json.fromString(s"${failure.getClass.getName}: ${failure.getMessage}")
      )
      .as(errorResponse(status, message))

  private def reject(
      config: Config,
      log: JsonLog,
      ctx: ReqCtx,
      request: Request[IO],
      outboundUri: String,
      duration: Long,
      failure: Throwable
  ): IO[Response[IO]] =
    val (status, message) = classify(failure)
    val empty = BodySnapshot(None, "empty", 0L)
    for
      _ <- log.error(
        "app.outbound",
        "Upstream request failed",
        Some(ctx),
        "http_method" -> Json.fromString(request.method.name),
        "uri" -> Json.fromString(outboundUri),
        "duration" -> Json.fromLong(duration),
        "error" -> Json.fromString(s"${failure.getClass.getName}: ${failure.getMessage}")
      )
      _ <- log.info(
        "app.http",
        "Method proxy returned response",
        Some(ctx),
        (List(
          "code" -> Json.fromInt(status.code),
          "raw_body" -> empty.rawBody,
          "body" -> Json.fromString("")
        ) ++ debugObject(config, request))*
      )
    yield errorResponse(status, message)

  private def operation(
      config: Config,
      log: JsonLog,
      request: Request[IO],
      ctx: ReqCtx,
      status: Status,
      start: Instant,
      duration: Long,
      level: LogLevel = LogLevel.Info
  ): IO[Unit] =
    val details = List("http_code" -> Json.fromString(status.code.toString)) ++
      copied(request.headers, ci"X-Username", "username") ++
      copied(request.headers, ci"X-Procedure-Run-Id", "procedure_run_id") ++
      copied(request.headers, ci"X-Client-Id", "client_id") ++
      copied(request.headers, ci"User-Agent", "user_agent")
    IO(
      log.write(
        level,
        "app.operations",
        "api operation executed",
        Some(ctx),
        List(
          "api" -> Json.fromString(config.serviceName),
          "path" -> Json.fromString(path(request)),
          "uri" -> Json.fromString(inboundAuthority(request).getOrElse("") + path(request)),
          "ip" -> Json.fromString(clientIp(request)),
          "start_time" -> Json.fromString(JsonLog.isoZ(start)),
          "duration_ms" -> Json.fromLong(duration),
          "result" -> Json.fromBoolean(status.code < 400),
          "details" -> Json.fromFields(details)
        )
      )
    )

  // healthz never proxies anything, so it gets a reduced lifecycle (received + operation, no forward/relay lines)
  // logged at DEBUG only: log.debug is what makes it silent at every other level, not a call-site level check
  private def health(config: Config, log: JsonLog, request: Request[IO]): IO[Response[IO]] =
    for
      ctx <- newContext(request)
      start <- IO.realTimeInstant
      begin <- IO.monotonic
      _ <- log.debug(
        "app.http",
        "Received http request",
        Some(ctx),
        (List(
          "path" -> Json.fromString(path(request)),
          "uri" -> Json.fromString(fullUri(request)),
          "headers" -> headersJson(request.headers)
        ) ++ debugObject(config, request))*
      )
      reachable <- upstreamReachable(config)
      end <- IO.monotonic
      response =
        if reachable then jsonResponse(Status.Ok, Json.obj("status" -> Json.fromString("ok")))
        else jsonResponse(Status.ServiceUnavailable, Json.obj("status" -> Json.fromString("upstream unreachable")))
      _ <- operation(config, log, request, ctx, response.status, start, (end - begin).toMillis, level = LogLevel.Debug)
    yield response

  private def upstreamReachable(config: Config): IO[Boolean] =
    (IpHost.fromString(config.upstreamHost), Port.fromInt(config.upstreamPort))
      .mapN(SocketAddress(_, _))
      .fold(IO.pure(false))(address =>
        Network[IO].connect(address).use_.timeout(HealthTimeout).as(true).handleError(_ => false)
      )

  private def authorized(config: Config, request: Request[IO]): Boolean =
    config.proxyToken.forall: expected =>
      headerValue(request.headers, TokenHeader).exists: provided =>
        MessageDigest.isEqual(provided.getBytes(UTF_8), expected.getBytes(UTF_8))

  private def newContext(request: Request[IO]): IO[ReqCtx] =
    for
      generated <- IO(UUID.randomUUID().toString)
      fallback <- IO(UUID.randomUUID().toString.replace("-", ""))
      span <- IO(spanId())
    yield ReqCtx(
      requestId = requestIdOf(request.headers).getOrElse(generated),
      traceId = traceId(request.headers).getOrElse(fallback),
      spanId = span,
      method = request.method.name,
      route = path(request)
    )

  // a caller-supplied header that is present but blank or whitespace-only counts as absent, not as an empty id to forward
  private def requestIdOf(headers: Headers): Option[String] =
    headerValue(headers, RequestIdHeader).map(_.trim).filter(_.nonEmpty)

  private val traceparent = "[0-9a-fA-F]{2}-([0-9a-fA-F]{32})-[0-9a-fA-F]{16}-[0-9a-fA-F]{2}".r

  private def traceId(headers: Headers): Option[String] =
    headerValue(headers, ci"x-b3-traceid")
      .orElse(headerValue(headers, ci"traceparent").collect { case traceparent(id) => id })

  private def spanId(): String =
    val bytes = new Array[Byte](8)
    scala.util.Random.nextBytes(bytes)
    bytes.map(b => f"${b & 0xff}%02x").mkString

  private def upstreamUri(config: Config, uri: Uri): Uri =
    Uri(
      scheme = Some(Uri.Scheme.http),
      authority = Some(Uri.Authority(host = Uri.RegName(config.upstreamHost), port = Some(config.upstreamPort))),
      path = uri.path,
      query = uri.query
    )

  private def forwardHeaders(config: Config, headers: Headers, requestId: String): Headers =
    val kept = headers.headers.filterNot(h =>
      hopByHop(h.name) || h.name == HostHeader || h.name == TokenHeader || h.name == RequestIdHeader ||
        (config.userAgentOverride && h.name == UserAgentHeader)
    )
    val withUserAgent =
      if config.userAgentOverride then Header.Raw(UserAgentHeader, Version.userAgent) :: kept else kept
    // ember only derives Host from the request uri when the request carries none, so this must come along untouched
    // the caller's own X-Request-Id (if any) is dropped above and replaced with the request's resolved id, so exactly one reaches upstream
    Headers(Header.Raw(HostHeader, config.targetAuthority) :: Header.Raw(RequestIdHeader, requestId) :: withUserAgent)

  private def responseHeaders(headers: Headers): Headers =
    Headers(headers.headers.filterNot(h => hopByHop(h.name)))

  private final case class BodySnapshot(bytes: Option[Array[Byte]], bodyType: String, length: Long):
    def rawBody: Json =
      Json.obj("body_type" -> Json.fromString(bodyType), "body_length" -> Json.fromLong(length))

  // ponytail: bodies still get buffered here for type detection even with LOG_BODIES off; LOG_BODY_MAX_BYTES=0 is what skips that cost
  private def snapshotBody(
      headers: Headers,
      body: Stream[IO, Byte],
      maxBytes: Long
  ): IO[(BodySnapshot, Stream[IO, Byte])] =
    val declared = headerValue(headers, ci"Content-Length").flatMap(_.toLongOption)
    val chunked = headerValue(headers, ci"Transfer-Encoding").exists(_.toLowerCase.contains("chunked"))
    if declared.contains(0L) || (declared.isEmpty && !chunked) then IO.pure((BodySnapshot(None, "empty", 0L), body))
    else if declared.forall(_ > maxBytes) then IO.pure((BodySnapshot(None, "unread", declared.getOrElse(0L)), body))
    else
      body.compile
        .to(Array)
        .map: bytes =>
          val bodyType = detectType(
            bytes,
            headerValue(headers, ci"Content-Type"),
            headerValue(headers, ci"Content-Encoding")
          )
          val snapshot = BodySnapshot(Some(bytes), bodyType, bytes.length.toLong)
          (snapshot, Stream.emits(bytes))

  private def detectType(bytes: Array[Byte], contentType: Option[String], contentEncoding: Option[String]): String =
    val head = new String(bytes.take(16), ISO_8859_1).stripLeading
    val kind = contentType.getOrElse("").toLowerCase
    // an encoded body is opaque bytes regardless of what Content-Type claims; parsing it as text/json would be garbage
    val encoded = contentEncoding.exists(_.trim.toLowerCase != "identity")
    if bytes.isEmpty then "empty"
    else if encoded then "binary"
    else if head.startsWith("%PDF-") then "pdf"
    else if head.startsWith("PK") then "docx"
    else if kind.contains("multipart") then "multipart"
    else if kind.contains("json") || head.startsWith("{") || head.startsWith("[") then "json"
    else if kind.contains("text") || kind.contains("xml") then "text"
    else "binary"

  private def renderBody(snapshot: BodySnapshot, logBodies: Boolean, logLevel: LogLevel): String =
    val debug = logLevel == LogLevel.Debug
    // json always renders; text/multipart only start rendering at DEBUG; unread/empty/binary/pdf/docx never do, at any level
    val renderable =
      snapshot.bodyType == "json" || (debug && (snapshot.bodyType == "text" || snapshot.bodyType == "multipart"))
    if !renderable then ""
    else if !logBodies then "logging is disabled by flag" // LOG_BODIES wins over DEBUG
    else
      snapshot.bodyType match
        case "json" =>
          snapshot.bytes
            .flatMap(bytes => parser.parse(new String(bytes, UTF_8)).toOption)
            .fold(""): json =>
              val masked = Masking.maskJson(json).noSpaces // masking applies before any clip decision, at every level
              if debug then masked
              else if snapshot.length > LogBodyLimit then s"<json ${snapshot.length} bytes>"
              else Masking.clip(masked, BodyClip)
        case _ => // text or multipart, only reachable when debug is true
          snapshot.bytes.map(bytes => new String(bytes, UTF_8)).getOrElse("")

  private def errorResponse(status: Status, message: String): Response[IO] =
    jsonResponse(status, Json.obj("error" -> Json.fromString(message)))

  private def jsonResponse(status: Status, body: Json): Response[IO] =
    Response[IO](status).withEntity(body.noSpaces).withContentType(`Content-Type`(MediaType.application.json))

  private def clientIp(request: Request[IO]): String =
    headerValue(request.headers, ci"X-Real-Ip")
      .orElse(headerValue(request.headers, ci"X-Forwarded-For").map(_.split(",").head.trim))
      .orElse(request.remoteAddr.map(_.toString))
      .getOrElse("")

  private def copied(headers: Headers, name: CIString, field: String): List[(String, Json)] =
    headerValue(headers, name).map(value => field -> Json.fromString(value)).toList

  private def headerValue(headers: Headers, name: CIString): Option[String] =
    headers.get(name).map(_.head.value).filter(_.nonEmpty)

  private def headersJson(headers: Headers): Json =
    Json.fromValues(Masking.headerLines(headers.headers.map(h => h.name.toString -> h.value)).map(Json.fromString))

  private def path(request: Request[IO]): String = request.uri.path.renderString

  private def inboundAuthority(request: Request[IO]): Option[String] =
    headerValue(request.headers, HostHeader).orElse(request.uri.authority.map(_.renderString))

  private def fullUri(request: Request[IO]): String =
    val relative = request.uri.copy(scheme = None, authority = None).renderString
    inboundAuthority(request).fold(relative)(authority => s"http://$authority$relative")

  // DEBUG-only extra detail folded into the request/response lifecycle lines; empty (and free to compute) at every other level
  private def debugObject(
      config: Config,
      request: Request[IO],
      responseContentType: Option[String] = None,
      responseContentLength: Option[Long] = None
  ): List[(String, Json)] =
    if config.logLevel != LogLevel.Debug then Nil
    else
      val fields = requestDebugFields(config, request) ++
        responseContentType.map(ct => "response_content_type" -> Json.fromString(ct)).toList ++
        responseContentLength.map(len => "response_content_length" -> Json.fromLong(len)).toList
      List("debug" -> Json.fromFields(fields))

  private def requestDebugFields(config: Config, request: Request[IO]): List[(String, Json)] =
    List(
      "http_version" -> Json.fromString(request.httpVersion.renderString),
      "query_params" -> queryParamsJson(request.uri),
      "outbound_uri" -> Json.fromString(outboundLogicalUri(config, request))
    ) ++
      request.remote.map(addr => "remote_addr" -> Json.fromString(addr.toString)).toList ++
      headerValue(request.headers, ci"Content-Type").map(ct => "request_content_type" -> Json.fromString(ct)).toList ++
      headerValue(request.headers, ci"Content-Length")
        .flatMap(_.toLongOption)
        .map(len => "request_content_length" -> Json.fromLong(len))
        .toList

  private def queryParamsJson(uri: Uri): Json =
    Json.fromFields(uri.query.multiParams.map { case (key, values) =>
      key -> Json.fromValues(values.map(Json.fromString))
    })

  // the logical target URI including the query string, which the non-debug "uri" fields never carry
  private def outboundLogicalUri(config: Config, request: Request[IO]): String =
    Uri.unsafeFromString(config.targetUrl).copy(path = request.uri.path, query = request.uri.query).renderString
