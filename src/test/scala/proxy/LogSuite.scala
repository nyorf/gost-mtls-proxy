package proxy

import cats.effect.IO
import fs2.Stream
import io.circe.parser
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8

final class LogSuite extends CatsEffectSuite:
  import TestSupport.*

  private val jsonUpstream: HttpApp[IO] = HttpApp[IO]: request =>
    request.body.compile.drain.as(Response[IO](Status.Ok).withEntity("""{"ok":true,"password":"leak"}"""))

  private def logged(request: Request[IO], extra: (String, String)*): IO[LogLines] =
    val lines = new LogLines
    upstream(jsonUpstream).use: port =>
      val cfg = config(port, extra*)
      send(cfg, logger(cfg, lines), request).as(lines)

  private def jsonRequest(path: String, body: String, headers: Header.ToRaw*): Request[IO] =
    val bytes = body.getBytes(UTF_8)
    Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withBodyStream(Stream.emits(bytes))
      .putHeaders(
        Header.Raw(CIString("Content-Type"), "application/json"),
        Header.Raw(CIString("Content-Length"), bytes.length.toString)
      )
      .putHeaders(headers*)

  private def textRequest(path: String, body: String, contentType: String): Request[IO] =
    val bytes = body.getBytes(UTF_8)
    Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withBodyStream(Stream.emits(bytes))
      .putHeaders(
        Header.Raw(CIString("Content-Type"), contentType),
        Header.Raw(CIString("Content-Length"), bytes.length.toString)
      )

  test("every line carries the base envelope and the request context") {
    logged(Request[IO](Method.GET, uri"/api/thing"), "CI_COMMIT" -> "deadbeef", "CI_REF" -> "main").map: lines =>
      assert(lines.parsed.nonEmpty)
      lines.parsed.foreach: line =>
        val cursor = line.hcursor
        assert(cursor.get[String]("message").isRight, line.noSpaces)
        assert(Set("INFO", "WARNING", "ERROR").contains(cursor.get[String]("level").toOption.get))
        assert(
          cursor
            .get[String]("@timestamp")
            .toOption
            .get
            .matches("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}\+00:00"""),
          cursor.get[String]("@timestamp").toString
        )
        assert(Set("app.http", "app.operations", "app.outbound").contains(cursor.get[String]("logger").toOption.get))
        assertEquals(cursor.get[String]("system"), Right("gost-mtls-proxy"))
        assertEquals(cursor.get[String]("env"), Right("dev"))
        assertEquals(cursor.get[String]("inst"), Right("test-host"))
        assertEquals(cursor.downField("ci").get[String]("deployed_at"), Right("2026-01-01T00:00:00.000000+00:00"))
        assertEquals(cursor.downField("ci").get[String]("commit"), Right("deadbeef"))
        assertEquals(cursor.downField("ci").get[String]("ref"), Right("main"))
        assert(cursor.get[String]("request_id").isRight)
        assert(cursor.get[String]("trace-id").isRight)
        assertEquals(cursor.get[String]("span-id").map(_.length), Right(16))
        assertEquals(cursor.get[String]("method"), Right("GET"))
        assertEquals(cursor.get[String]("route"), Right("/api/thing"))
  }

  test("the lifecycle emits the four contract lines in order") {
    logged(jsonRequest("/api/thing", """{"a":1}""")).map: lines =>
      assertEquals(
        lines.parsed.map(_.hcursor.get[String]("message").toOption.get),
        List(
          "Received http request",
          "Method proxy was called for /api/thing with body 'json'",
          "Sending http request",
          "Got http response with code=200",
          "Method proxy returned response",
          "api operation executed"
        )
      )
  }

  test("the operations line reports the code, the caller and the copied headers") {
    val request = Request[IO](Method.GET, uri"/api/thing?x=1").putHeaders(
      Header.Raw(CIString("Host"), "proxy.local:8080"),
      Header.Raw(CIString("X-Username"), "ivanov"),
      Header.Raw(CIString("X-Procedure-Run-Id"), "run-7"),
      Header.Raw(CIString("X-Client-Id"), "client-9"),
      Header.Raw(CIString("User-Agent"), "curl/8"),
      Header.Raw(CIString("X-Forwarded-For"), "203.0.113.9, 10.0.0.1")
    )
    logged(request).map: lines =>
      val operation = lines.withMessage("api operation executed").head.hcursor
      assertEquals(operation.get[String]("api"), Right("gost-mtls-proxy"))
      assertEquals(operation.get[String]("path"), Right("/api/thing"))
      assertEquals(operation.get[String]("uri"), Right("proxy.local:8080/api/thing"))
      assertEquals(operation.get[String]("ip"), Right("203.0.113.9"))
      assertEquals(operation.get[Boolean]("result"), Right(true))
      assert(operation.get[Long]("duration_ms").isRight)
      assert(operation.get[String]("start_time").toOption.get.endsWith("Z"))
      val details = operation.downField("details")
      assertEquals(details.get[String]("http_code"), Right("200"))
      assertEquals(details.get[String]("username"), Right("ivanov"))
      assertEquals(details.get[String]("procedure_run_id"), Right("run-7"))
      assertEquals(details.get[String]("client_id"), Right("client-9"))
      assertEquals(details.get[String]("user_agent"), Right("curl/8"))
  }

  test("masks sensitive headers and json fields, and keeps non-ascii unescaped") {
    val request = jsonRequest(
      "/submit",
      """{"email":"ivan@example.com","token":"abc","имя":"Иван","nested":[{"password":"p"}]}""",
      Header.Raw(CIString("Authorization"), "Bearer top-secret"),
      Header.Raw(CIString("Cookie"), "session=1"),
      Header.Raw(CIString("X-Api-Key"), "key")
    )
    logged(request).map: lines =>
      val headers = lines.withMessage("Received http request").head.hcursor.get[List[String]]("headers").toOption.get
      assert(headers.contains("Authorization: ***"), headers.toString)
      assert(headers.contains("Cookie: ***"))
      assert(headers.contains("X-Api-Key: ***"))
      assert(!lines.raw.exists(_.contains("top-secret")), "authorization value leaked")

      val called = lines.withMessage("Method proxy was called for /submit with body 'json'").head.hcursor
      val body = parser.parse(called.get[String]("body").toOption.get).fold(throw _, _.hcursor)
      assertEquals(body.get[String]("email"), Right("ivan@example.com"))
      assertEquals(body.get[String]("token"), Right("***"))
      assertEquals(body.downField("nested").downN(0).get[String]("password"), Right("***"))
      assertEquals(body.get[String]("имя"), Right("Иван"))
      assert(lines.raw.exists(_.contains("Иван")), "cyrillic must not be escaped")

      val returned = lines.withMessage("Method proxy returned response").head.hcursor
      assertEquals(returned.get[Int]("code"), Right(200))
      assertEquals(returned.downField("raw_body").get[String]("body_type"), Right("json"))
      assert(returned.get[String]("body").toOption.get.contains("\"password\":\"***\""))
  }

  test("the outbound header is overridden but details.user_agent still reports the caller's own value") {
    val withUa = Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("User-Agent"), "curl/8"))
    for
      withHeader <- logged(withUa)
      withoutHeader <- logged(Request[IO](Method.GET, uri"/x"))
    yield
      val sent = withHeader.withMessage("Sending http request").head.hcursor.get[List[String]]("headers").toOption.get
      assert(sent.contains(s"User-Agent: ${Version.userAgent}"), sent.toString)

      val detailsWith = withHeader.withMessage("api operation executed").head.hcursor.downField("details")
      assertEquals(detailsWith.get[String]("user_agent"), Right("curl/8"))

      val detailsWithout = withoutHeader.withMessage("api operation executed").head.hcursor.downField("details")
      assert(
        !detailsWithout.downField("user_agent").succeeded,
        "user_agent must be absent, not empty, when the caller sent none"
      )
  }

  test("reports the outbound target authority, not the stunnel address") {
    logged(Request[IO](Method.GET, uri"/api/thing")).map: lines =>
      val sending = lines.withMessage("Sending http request").head.hcursor
      assertEquals(sending.get[String]("uri"), Right(s"https://$targetAuthority/api/thing"))
      assertEquals(sending.get[String]("http_method"), Right("GET"))
      assert(sending.get[List[String]]("headers").toOption.get.contains(s"Host: $targetAuthority"))
      val received = lines.withMessage("Got http response with code=200").head.hcursor
      assertEquals(received.get[String]("uri"), Right(s"https://$targetAuthority/api/thing"))
      assert(received.get[Long]("duration").isRight)
  }

  test("takes trace-id from traceparent, and x-b3-traceid wins") {
    val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    for
      fromTraceparent <- logged(
        Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("traceparent"), traceparent))
      )
      fromB3 <- logged(
        Request[IO](Method.GET, uri"/x").putHeaders(
          Header.Raw(CIString("traceparent"), traceparent),
          Header.Raw(CIString("x-b3-traceid"), "b3trace")
        )
      )
      malformed <- logged(Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("traceparent"), "nonsense")))
    yield
      assertEquals(traceOf(fromTraceparent), "4bf92f3577b34da6a3ce929d0e0e4736")
      assertEquals(traceOf(fromB3), "b3trace")
      assertEquals(traceOf(malformed).length, 32)
  }

  test("healthz is silent") {
    upstream(jsonUpstream)
      .use: port =>
        val lines = new LogLines
        val cfg = config(port)
        send(cfg, logger(cfg, lines), Request[IO](Method.GET, uri"/healthz")).as(lines)
      .map(lines => assertEquals(lines.raw, Nil))
  }

  test("a rejected request still reports the operation") {
    logged(Request[IO](Method.GET, uri"/x"), "PROXY_TOKEN" -> "expected").map: lines =>
      val operation = lines.withMessage("api operation executed").head.hcursor
      assertEquals(operation.downField("details").get[String]("http_code"), Right("401"))
      assertEquals(operation.get[Boolean]("result"), Right(false))
      assertEquals(lines.withMessage("Sending http request"), Nil)
  }

  test("never logs the proxy token") {
    val request = Request[IO](Method.GET, uri"/x").putHeaders(Header.Raw(CIString("X-Proxy-Token"), "s3cret"))
    logged(request, "PROXY_TOKEN" -> "s3cret").map: lines =>
      assert(!lines.raw.exists(_.contains("s3cret")), "proxy token leaked into the logs")
      val headers = lines.withMessage("Received http request").head.hcursor.get[List[String]]("headers").toOption.get
      assert(headers.contains("X-Proxy-Token: ***"), headers.toString)
  }

  test("an unreachable upstream reports the failure and a 502 operation") {
    closedPort
      .flatMap: port =>
        val lines = new LogLines
        val cfg = config(port)
        send(cfg, logger(cfg, lines), Request[IO](Method.GET, uri"/x")).as(lines)
      .map: lines =>
        val operation = lines.withMessage("api operation executed").head.hcursor
        assertEquals(operation.downField("details").get[String]("http_code"), Right("502"))
        assertEquals(operation.get[Boolean]("result"), Right(false))
        assertEquals(lines.withMessage("Upstream request failed").head.hcursor.get[String]("level"), Right("ERROR"))
  }

  test("LOG_BODIES=false keeps the body out of the logs but keeps its shape") {
    logged(jsonRequest("/submit", """{"email":"ivan@example.com"}"""), "LOG_BODIES" -> "false").map: lines =>
      val called = lines.withMessage("Method proxy was called for /submit with body 'json'").head.hcursor
      assertEquals(called.get[String]("body"), Right("logging is disabled by flag"))
      assertEquals(called.downField("raw_body").get[String]("body_type"), Right("json"))
      assertEquals(called.downField("raw_body").get[Long]("body_length"), Right(28L))
      assert(!lines.raw.exists(_.contains("ivan@example.com")))
  }

  test("the startup line renders the resolved config without the token") {
    val lines = new LogLines
    val cfg = config(1234, "PROXY_TOKEN" -> "s3cret")
    val log = logger(cfg, lines)
    log
      .info("app.operations", "startup config", None, "config" -> cfg.nonSecret)
      .map: _ =>
        val startup = lines.withMessage("startup config").head.hcursor.downField("config")
        assertEquals(startup.get[String]("target_url"), Right(s"https://$targetAuthority"))
        assertEquals(startup.get[Boolean]("proxy_token"), Right(true))
        assert(!lines.raw.exists(_.contains("s3cret")))
  }

  test("the generated X-Request-Id is forwarded to the upstream and matches the logged request_id") {
    logged(Request[IO](Method.GET, uri"/x")).map: lines =>
      val requestId = lines.withMessage("Received http request").head.hcursor.get[String]("request_id").toOption.get
      val sentHeaders = lines.withMessage("Sending http request").head.hcursor.get[List[String]]("headers").toOption.get
      assertEquals(sentHeaders.count(_.startsWith("X-Request-Id:")), 1)
      assert(sentHeaders.contains(s"X-Request-Id: $requestId"), sentHeaders.toString)
  }

  test("LOG_LEVEL=ERROR silences the lifecycle and operation lines for a successful request") {
    logged(Request[IO](Method.GET, uri"/api/thing"), "LOG_LEVEL" -> "ERROR").map: lines =>
      assertEquals(lines.raw, Nil)
  }

  test("LOG_LEVEL=DEBUG renders a large json body in full, skipping the clip and the size summary") {
    val big = "a" * 300000
    val payload = s"""{"data":"$big"}"""
    logged(jsonRequest("/big-body", payload), "LOG_LEVEL" -> "DEBUG").map: lines =>
      val body = lines
        .withMessage("Method proxy was called for /big-body with body 'json'")
        .head
        .hcursor
        .get[String]("body")
        .toOption
        .get
      assert(!body.contains("(truncated)"), body.take(80))
      assert(!body.contains("<json"), body.take(80))
      assertEquals(parser.parse(body).fold(throw _, _.hcursor).get[String]("data"), Right(big))
  }

  test("a secret in the request body stays masked at DEBUG, alongside the rest of the body rendered in full") {
    val longNote = "x" * 20000
    val payload = s"""{"password":"topsecret123","note":"$longNote"}"""
    logged(jsonRequest("/submit", payload), "LOG_LEVEL" -> "DEBUG").map: lines =>
      assert(!lines.raw.exists(_.contains("topsecret123")), "secret leaked into the log at DEBUG")
      val body = lines
        .withMessage("Method proxy was called for /submit with body 'json'")
        .head
        .hcursor
        .get[String]("body")
        .toOption
        .get
      val cursor = parser.parse(body).fold(throw _, _.hcursor)
      assertEquals(cursor.get[String]("password"), Right("***"))
      assertEquals(cursor.get[String]("note"), Right(longNote))
  }

  test("DEBUG renders text and multipart bodies as text instead of the empty string they produce below DEBUG") {
    val multipartBody = "--boundary\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--boundary--"
    for
      text <- logged(textRequest("/text", "hello world", "text/plain"), "LOG_LEVEL" -> "DEBUG")
      multipart <- logged(
        textRequest("/mp", multipartBody, "multipart/form-data; boundary=boundary"),
        "LOG_LEVEL" -> "DEBUG"
      )
      belowDebug <- logged(textRequest("/text", "hello world", "text/plain"))
    yield
      val calledText = bodyOf(text, "Method proxy was called for /text with body 'text'")
      assertEquals(calledText, "hello world")
      val calledMultipart = bodyOf(multipart, "Method proxy was called for /mp with body 'multipart'")
      assert(calledMultipart.contains("Content-Disposition"), calledMultipart)
      assertEquals(bodyOf(belowDebug, "Method proxy was called for /text with body 'text'"), "")
  }

  test("LOG_BODIES=false wins over DEBUG for every body type that would otherwise render") {
    for
      jsonLines <- logged(jsonRequest("/submit", """{"a":1}"""), "LOG_LEVEL" -> "DEBUG", "LOG_BODIES" -> "false")
      textLines <- logged(textRequest("/text", "hello", "text/plain"), "LOG_LEVEL" -> "DEBUG", "LOG_BODIES" -> "false")
    yield
      assertEquals(
        bodyOf(jsonLines, "Method proxy was called for /submit with body 'json'"),
        "logging is disabled by flag"
      )
      assertEquals(
        bodyOf(textLines, "Method proxy was called for /text with body 'text'"),
        "logging is disabled by flag"
      )
  }

  test("the debug object appears on the request/response lifecycle lines only at LOG_LEVEL=DEBUG") {
    val request = Request[IO](Method.GET, uri"/api/thing?x=1&x=2")
    for
      debugLines <- logged(request, "LOG_LEVEL" -> "DEBUG")
      infoLines <- logged(request)
    yield
      val received = debugLines.withMessage("Received http request").head.hcursor.downField("debug")
      assert(received.succeeded, debugLines.withMessage("Received http request").head.noSpaces)
      assertEquals(received.get[String]("http_version"), Right("HTTP/1.1"))
      assertEquals(received.downField("query_params").downField("x").as[List[String]], Right(List("1", "2")))
      assert(received.get[String]("outbound_uri").toOption.get.endsWith("/api/thing?x=1&x=2"))

      val returned = debugLines.withMessage("Method proxy returned response").head.hcursor.downField("debug")
      assert(returned.succeeded)

      val receivedAtInfo = infoLines.withMessage("Received http request").head.hcursor
      assert(!receivedAtInfo.downField("debug").succeeded, "the debug object must be absent below DEBUG")
  }

  test("healthz emits its lifecycle lines at LOG_LEVEL=DEBUG only, and only at DEBUG") {
    upstream(jsonUpstream)
      .use: port =>
        val lines = new LogLines
        val cfg = config(port, "LOG_LEVEL" -> "DEBUG")
        send(cfg, logger(cfg, lines), Request[IO](Method.GET, uri"/healthz")).as(lines)
      .map: lines =>
        assertEquals(
          lines.parsed.map(_.hcursor.get[String]("message").toOption.get),
          List("Received http request", "api operation executed")
        )
        assert(lines.parsed.forall(_.hcursor.get[String]("level").contains("DEBUG")), lines.raw.toString)
  }

  private def bodyOf(lines: LogLines, message: String): String =
    lines.withMessage(message).head.hcursor.get[String]("body").toOption.get

  private def traceOf(lines: LogLines): String =
    lines.parsed.head.hcursor.get[String]("trace-id").toOption.get
