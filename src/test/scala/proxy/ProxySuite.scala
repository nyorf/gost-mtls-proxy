package proxy

import cats.effect.{IO, Ref}
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

import java.io.ByteArrayOutputStream
import java.net.{InetAddress, ServerSocket}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

final class ProxySuite extends CatsEffectSuite:
  import TestSupport.*

  private def gzip(text: String): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val zipped = new GZIPOutputStream(out)
    zipped.write(text.getBytes(UTF_8))
    zipped.close()
    out.toByteArray

  private def proxied(request: Request[IO], respond: Response[IO] = Response[IO](Status.Ok)): IO[(Captured, Result)] =
    for
      seen <- Ref[IO].of(Option.empty[Captured])
      result <- upstream(capturing(seen, respond)).use: port =>
        val cfg = config(port)
        send(cfg, silent(cfg), request)
      captured <- seen.get
    yield (captured.getOrElse(throw new AssertionError("upstream received nothing")), result)

  test("sends the target authority as the only Host header") {
    proxied(Request[IO](Method.GET, uri"/anything")).map: (captured, _) =>
      assertEquals(captured.all("Host"), List(targetAuthority))
  }

  test("preserves method, encoded path, repeated query params and body") {
    val target = uri"/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82/a%2Fb?x=1&x=2&y=%D0%B0"
    val body = "тело=да".getBytes(UTF_8)
    val request = Request[IO](Method.PUT, target)
      .withBodyStream(Stream.emits(body))
      .putHeaders(Header.Raw(CIString("Content-Length"), body.length.toString))
    proxied(request).map: (captured, _) =>
      assertEquals(captured.method, Method.PUT)
      assertEquals(captured.uri.path.renderString, "/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82/a%2Fb")
      assertEquals(captured.uri.query.renderString, "x=1&x=2&y=%D0%B0")
      assertEquals(captured.uri.query.multiParams.get("x").map(_.toList), Some(List("1", "2")))
      assertEquals(captured.body.toList, body.toList)
  }

  test("passes Authorization and arbitrary headers through, strips X-Proxy-Token and hop-by-hop headers") {
    val request = Request[IO](Method.GET, uri"/x").putHeaders(
      Header.Raw(CIString("Authorization"), "Bearer secret-value"),
      Header.Raw(CIString("X-Anything"), "kept"),
      Header.Raw(CIString("X-Proxy-Token"), "shhh"),
      Header.Raw(CIString("Connection"), "keep-alive"),
      Header.Raw(CIString("Keep-Alive"), "timeout=5"),
      Header.Raw(CIString("Proxy-Authenticate"), "Basic"),
      Header.Raw(CIString("Proxy-Authorization"), "Basic zzz"),
      Header.Raw(CIString("TE"), "trailers"),
      Header.Raw(CIString("Trailer"), "X-Late"),
      Header.Raw(CIString("Upgrade"), "websocket")
    )
    proxied(request).map: (captured, _) =>
      assertEquals(captured.header("Authorization"), Some("Bearer secret-value"))
      assertEquals(captured.header("X-Anything"), Some("kept"))
      assertEquals(captured.header("X-Proxy-Token"), Option.empty[String])
      List("Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "TE", "Trailer", "Upgrade").foreach: name =>
        assertEquals(captured.header(name), Option.empty[String], s"$name should have been stripped")
  }

  test("strips X-Proxy-Token even when no token is configured") {
    val request = Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("X-Proxy-Token"), "anything"))
    proxied(request).map: (captured, _) =>
      assertEquals(captured.header("X-Proxy-Token"), Option.empty[String])
  }

  test("by default replaces the caller's User-Agent with a single overridden header") {
    val request = Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("User-Agent"), "curl/8"))
    proxied(request).map: (captured, _) =>
      assertEquals(captured.all("User-Agent"), List(Version.userAgent))
  }

  test("adds the overridden User-Agent even when the caller sent none") {
    proxied(Request[IO](Method.GET, uri"/x")).map: (captured, _) =>
      assertEquals(captured.all("User-Agent"), List(Version.userAgent))
  }

  test("USER_AGENT_OVERRIDE=false passes the caller's User-Agent through untouched") {
    val request = Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("User-Agent"), "curl/8"))
    for
      seen <- Ref[IO].of(Option.empty[Captured])
      _ <- upstream(capturing(seen)).use: port =>
        val cfg = config(port, "USER_AGENT_OVERRIDE" -> "false")
        send(cfg, silent(cfg), request)
      captured <- seen.get.map(_.getOrElse(throw new AssertionError("upstream received nothing")))
    yield assertEquals(captured.all("User-Agent"), List("curl/8"))
  }

  test("USER_AGENT_OVERRIDE=false adds no header when the caller sent none") {
    for
      seen <- Ref[IO].of(Option.empty[Captured])
      _ <- upstream(capturing(seen)).use: port =>
        val cfg = config(port, "USER_AGENT_OVERRIDE" -> "false")
        send(cfg, silent(cfg), Request[IO](Method.GET, uri"/x"))
      captured <- seen.get.map(_.getOrElse(throw new AssertionError("upstream received nothing")))
    yield assertEquals(captured.all("User-Agent"), Nil)
  }

  test("forwards a chunked body with no content-length") {
    val request = Request[IO](Method.POST, uri"/chunked")
      .withBodyStream(Stream.emits("streamed".getBytes(UTF_8)))
      .putHeaders(Header.Raw(CIString("Transfer-Encoding"), "chunked"))
    proxied(request).map: (captured, _) =>
      assertEquals(captured.text, "streamed")
      assertEquals(captured.header("Transfer-Encoding"), Some("chunked")) // re-framed by ember, not relayed verbatim
  }

  test("relays response status, headers and body, dropping hop-by-hop response headers") {
    val respond = Response[IO](Status.ImATeapot)
      .withEntity("чайник")
      .putHeaders(
        Header.Raw(CIString("X-Custom"), "relayed"),
        Header.Raw(CIString("Proxy-Authenticate"), "Basic")
      )
    proxied(Request[IO](Method.GET, uri"/teapot"), respond).map: (_, result) =>
      assertEquals(result.status, Status.ImATeapot)
      assertEquals(result.text, "чайник")
      assertEquals(result.header("X-Custom"), Some("relayed"))
      assertEquals(result.header("Proxy-Authenticate"), Option.empty[String])
  }

  test("echoes the inbound X-Request-Id and generates one otherwise") {
    val withId = Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("X-Request-Id"), "given-id"))
    for
      echoed <- proxied(withId)
      auto <- proxied(Request[IO](Method.GET, uri"/x"))
    yield
      assertEquals(echoed._2.header("X-Request-Id"), Some("given-id"))
      assert(auto._2.header("X-Request-Id").exists(_.length == 36), "expected a generated uuid request id")
  }

  test("forwards the caller's X-Request-Id to the upstream, exactly once") {
    val request = Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("X-Request-Id"), "given-id"))
    proxied(request).map: (captured, result) =>
      assertEquals(captured.all("X-Request-Id"), List("given-id"))
      assertEquals(result.header("X-Request-Id"), Some("given-id"))
  }

  test("generates a uuid4 X-Request-Id and forwards it upstream when the caller sent none") {
    proxied(Request[IO](Method.GET, uri"/x")).map: (captured, result) =>
      assertEquals(captured.all("X-Request-Id").length, 1)
      val generated = captured.header("X-Request-Id").get
      assert(generated.length == 36, generated)
      assertEquals(result.header("X-Request-Id"), Some(generated))
  }

  test("a blank X-Request-Id from the caller is replaced with a fresh uuid4, forwarded and echoed") {
    val request = Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("X-Request-Id"), "   "))
    proxied(request).map: (captured, result) =>
      assertEquals(captured.all("X-Request-Id").length, 1)
      val generated = captured.header("X-Request-Id").get
      assert(generated.length == 36, generated)
      assert(generated != "   ")
      assertEquals(result.header("X-Request-Id"), Some(generated))
  }

  test("rejects a missing or wrong proxy token and accepts the right one") {
    val token = "s3cret"
    for
      seen <- Ref[IO].of(Option.empty[Captured])
      results <- upstream(capturing(seen)).use: port =>
        val cfg = config(port, "PROXY_TOKEN" -> token)
        val log = silent(cfg)
        for
          missing <- send(cfg, log, Request[IO](Method.GET, uri"/x"))
          wrong <- send(
            cfg,
            log,
            Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("X-Proxy-Token"), "nope"))
          )
          right <- send(
            cfg,
            log,
            Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("X-Proxy-Token"), token))
          )
          health <- send(cfg, log, Request[IO](Method.GET, uri"/healthz"))
        yield (missing, wrong, right, health)
      untouched <- seen.get.map(_.forall(_.header("X-Proxy-Token").isEmpty))
    yield
      val (missing, wrong, right, health) = results
      assertEquals(missing.status, Status.Unauthorized)
      assertEquals(missing.text, """{"error":"unauthorized"}""")
      assertEquals(wrong.status, Status.Unauthorized)
      assertEquals(right.status, Status.Ok)
      assertEquals(health.status, Status.Ok)
      assert(untouched)
  }

  test("healthz reports ok when the upstream port accepts connections and 503 when it does not") {
    for
      ok <- upstream(HttpApp.notFound[IO]).use: port =>
        val cfg = config(port)
        send(cfg, silent(cfg), Request[IO](Method.GET, uri"/healthz"))
      dead <- closedPort.flatMap: port =>
        val cfg = config(port)
        send(cfg, silent(cfg), Request[IO](Method.GET, uri"/healthz"))
    yield
      assertEquals(ok.status, Status.Ok)
      assertEquals(ok.text, """{"status":"ok"}""")
      assertEquals(dead.status, Status.ServiceUnavailable)
      assertEquals(dead.text, """{"status":"upstream unreachable"}""")
  }

  test("answers 502 when the upstream refuses the connection") {
    closedPort
      .flatMap: port =>
        val cfg = config(port)
        send(cfg, silent(cfg), Request[IO](Method.GET, uri"/x"))
      .map: result =>
        assertEquals(result.status, Status.BadGateway)
        assertEquals(result.text, """{"error":"upstream unreachable"}""")
  }

  test("streams a body larger than the logging cap without materialising it") {
    val kilobyte = Array.tabulate(1024)(index => (index % 256).toByte)
    val chunks = 11 * 1024
    val size = chunks.toLong * kilobyte.length
    val expected = MessageDigest.getInstance("SHA-256")
    (0 until chunks).foreach(_ => expected.update(kilobyte))
    val expectedHash = expected.digest().toList

    val hashing = (seen: Ref[IO, Array[Byte]]) =>
      HttpApp[IO]: request =>
        IO(MessageDigest.getInstance("SHA-256")).flatMap: digest =>
          request.body.chunks
            .evalMap(chunk => IO(digest.update(chunk.toArray)))
            .compile
            .drain *> IO(digest.digest()).flatMap(seen.set).as(Response[IO](Status.Ok))

    val request = Request[IO](Method.POST, uri"/big")
      .withBodyStream(Stream.chunk(Chunk.array(kilobyte)).repeatN(chunks.toLong).covary[IO])
      .putHeaders(Header.Raw(CIString("Content-Length"), size.toString))

    val lines = new LogLines
    for
      seen <- Ref[IO].of(Array.emptyByteArray)
      result <- upstream(hashing(seen)).use: port =>
        val cfg = config(port)
        send(cfg, logger(cfg, lines), request)
      received <- seen.get
    yield
      assertEquals(result.status, Status.Ok)
      assertEquals(received.toList, expectedHash)
      val called = lines.withMessage("Method proxy was called for /big with body 'unread'")
      val rawBody = called.head.hcursor.downField("raw_body")
      assertEquals(rawBody.get[String]("body_type"), Right("unread"))
      assertEquals(rawBody.get[Long]("body_length"), Right(size))
  }

  test("a body read failure while relaying the upstream response yields the proxy's 502, not a bare 500") {
    val server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serve = IO.blocking {
      val socket = server.accept()
      try
        val out = socket.getOutputStream
        // promises 100 bytes, delivers 5, then hangs up: the client parses headers fine and only fails reading the body
        out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort".getBytes(UTF_8))
        out.flush()
      finally socket.close()
    }
    val lines = new LogLines
    for
      fiber <- serve.start
      cfg = config(server.getLocalPort)
      result <- send(cfg, logger(cfg, lines), Request[IO](Method.GET, uri"/x"))
      _ <- fiber.join
      _ <- IO.blocking(server.close())
    yield
      assertEquals(result.status, Status.BadGateway)
      assertEquals(result.text, """{"error":"upstream unreachable"}""")
      val operation = lines.withMessage("api operation executed")
      assertEquals(operation.length, 1)
      assertEquals(operation.head.hcursor.downField("details").get[String]("http_code"), Right("502"))
  }

  test("a gzip Content-Encoding is logged as binary, not json, even with a json Content-Type") {
    val respond = Response[IO](Status.Ok)
      .withEntity(gzip("""{"a":1}"""))
      .putHeaders(
        Header.Raw(CIString("Content-Type"), "application/json"),
        Header.Raw(CIString("Content-Encoding"), "gzip")
      )
    val lines = new LogLines
    for
      seen <- Ref[IO].of(Option.empty[Captured])
      _ <- upstream(capturing(seen, respond)).use: port =>
        val cfg = config(port)
        send(cfg, logger(cfg, lines), Request[IO](Method.GET, uri"/x"))
    yield
      val returned = lines.withMessage("Method proxy returned response")
      assertEquals(returned.length, 1)
      assertEquals(returned.head.hcursor.downField("raw_body").get[String]("body_type"), Right("binary"))
  }

  test(
    "a body just under LOG_BODY_MAX_BYTES is buffered and typed; a body just over is unread but forwarded byte-identical"
  ) {
    val threshold = 32L
    def paddedJson(len: Int): Array[Byte] = ("{" + ("x" * (len - 2)) + "}").getBytes(UTF_8)
    val under = paddedJson((threshold - 1).toInt)
    val over = paddedJson((threshold + 1).toInt)

    def jsonRequest(body: Array[Byte]): Request[IO] =
      Request[IO](Method.POST, uri"/x")
        .withBodyStream(Stream.emits(body))
        .putHeaders(
          Header.Raw(CIString("Content-Length"), body.length.toString),
          Header.Raw(CIString("Content-Type"), "application/json")
        )

    def sha256(bytes: Array[Byte]): List[Byte] = MessageDigest.getInstance("SHA-256").digest(bytes).toList

    val lines = new LogLines
    for
      seen <- Ref[IO].of(Option.empty[Captured])
      results <- upstream(capturing(seen)).use: port =>
        val cfg = config(port, "LOG_BODY_MAX_BYTES" -> threshold.toString)
        val log = logger(cfg, lines)
        for
          _ <- send(cfg, log, jsonRequest(under))
          underCaptured <- seen.get.map(_.getOrElse(throw new AssertionError("upstream received nothing")))
          _ <- send(cfg, log, jsonRequest(over))
          overCaptured <- seen.get.map(_.getOrElse(throw new AssertionError("upstream received nothing")))
        yield (underCaptured, overCaptured)
    yield
      val (underCaptured, overCaptured) = results
      assertEquals(sha256(underCaptured.body), sha256(under))
      assertEquals(sha256(overCaptured.body), sha256(over))
      val underLine = lines.withMessage("Method proxy was called for /x with body 'json'")
      assertEquals(underLine.length, 1)
      assertEquals(underLine.head.hcursor.downField("raw_body").get[String]("body_type"), Right("json"))
      val overLine = lines.withMessage("Method proxy was called for /x with body 'unread'")
      assertEquals(overLine.length, 1)
      assertEquals(overLine.head.hcursor.downField("raw_body").get[String]("body_type"), Right("unread"))
      assertEquals(overLine.head.hcursor.downField("raw_body").get[Long]("body_length"), Right(over.length.toLong))
  }

  test(
    "LOG_BODY_MAX_BYTES=0 buffers nothing: a json body is unread, never rendered even at DEBUG, and still arrives untouched"
  ) {
    val body = """{"secret":"value"}""".getBytes(UTF_8)
    val request = Request[IO](Method.POST, uri"/zero")
      .withBodyStream(Stream.emits(body))
      .putHeaders(
        Header.Raw(CIString("Content-Length"), body.length.toString),
        Header.Raw(CIString("Content-Type"), "application/json")
      )

    val lines = new LogLines
    for
      seen <- Ref[IO].of(Option.empty[Captured])
      captured <- upstream(capturing(seen)).use: port =>
        val cfg = config(port, "LOG_BODY_MAX_BYTES" -> "0", "LOG_LEVEL" -> "debug")
        send(cfg, logger(cfg, lines), request) *> seen.get.map(
          _.getOrElse(throw new AssertionError("upstream received nothing"))
        )
    yield
      assertEquals(captured.body.toList, body.toList)
      val called = lines.withMessage("Method proxy was called for /zero with body 'unread'")
      assertEquals(called.length, 1)
      assertEquals(called.head.hcursor.downField("raw_body").get[String]("body_type"), Right("unread"))
      assertEquals(called.head.hcursor.get[String]("body"), Right(""))
  }

  test("LOG_BODY_MAX_BYTES=0 still reports an empty body as empty, not unread") {
    val lines = new LogLines
    for
      seen <- Ref[IO].of(Option.empty[Captured])
      _ <- upstream(capturing(seen)).use: port =>
        val cfg = config(port, "LOG_BODY_MAX_BYTES" -> "0")
        send(cfg, logger(cfg, lines), Request[IO](Method.GET, uri"/empty"))
    yield
      val called = lines.withMessage("Method proxy was called for /empty with body 'empty'")
      assertEquals(called.length, 1)
      assertEquals(called.head.hcursor.downField("raw_body").get[String]("body_type"), Right("empty"))
  }

  test("an uncompressed json response is still logged as json") {
    val respond = Response[IO](Status.Ok)
      .withEntity("""{"a":1}""")
      .putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))
    val lines = new LogLines
    for
      seen <- Ref[IO].of(Option.empty[Captured])
      _ <- upstream(capturing(seen, respond)).use: port =>
        val cfg = config(port)
        send(cfg, logger(cfg, lines), Request[IO](Method.GET, uri"/x"))
    yield
      val returned = lines.withMessage("Method proxy returned response")
      assertEquals(returned.length, 1)
      assertEquals(returned.head.hcursor.downField("raw_body").get[String]("body_type"), Right("json"))
  }
