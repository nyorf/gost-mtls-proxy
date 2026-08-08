package proxy

import munit.FunSuite

final class ConfigSuite extends FunSuite:
  private def parse(entries: (String, String)*): Either[String, Config] =
    Config.from(entries.toMap)

  test("requires TARGET_URL") {
    assertEquals(parse(), Left("TARGET_URL is required"))
    assertEquals(parse("TARGET_URL" -> "  "), Left("TARGET_URL is required"))
  }

  test("rejects a TARGET_URL that carries more than an authority") {
    assert(parse("TARGET_URL" -> "https://api.example.com/v1").isLeft)
    assert(parse("TARGET_URL" -> "https://api.example.com?a=1").isLeft)
    assert(parse("TARGET_URL" -> "https://api.example.com#frag").isLeft)
    assert(parse("TARGET_URL" -> "http://api.example.com").isLeft)
    assert(parse("TARGET_URL" -> "api.example.com").isLeft)
  }

  test("keeps the authority form, port included") {
    assertEquals(parse("TARGET_URL" -> "https://api.example.com").map(_.targetAuthority), Right("api.example.com"))
    assertEquals(
      parse("TARGET_URL" -> "https://api.example.com:8443").map(_.targetAuthority),
      Right("api.example.com:8443")
    )
    assertEquals(parse("TARGET_URL" -> "https://api.example.com/").map(_.targetUrl), Right("https://api.example.com"))
  }

  test("applies defaults") {
    val config = parse("TARGET_URL" -> "https://api.example.com").fold(problem => fail(problem), identity)
    assertEquals(config.port, 8080)
    assertEquals(config.upstreamHost, "127.0.0.1")
    assertEquals(config.upstreamPort, 8443)
    assertEquals(config.proxyToken, None)
    assertEquals(config.logBodies, true)
    assertEquals(config.logBodyMaxBytes, 10485760L)
    assertEquals(config.logLevel, LogLevel.Info)
    assertEquals(config.userAgentOverride, true)
    assertEquals(config.serviceName, "gost-mtls-proxy")
    assertEquals(config.env, "dev")
    assertEquals(config.ciCommit, None)
    assertEquals(config.ciRef, None)
  }

  test("reads overrides and lowercases ENV") {
    val config = parse(
      "TARGET_URL" -> "https://api.example.com",
      "PORT" -> "9000",
      "UPSTREAM_HOST" -> "stunnel",
      "UPSTREAM_PORT" -> "9443",
      "PROXY_TOKEN" -> "abc",
      "LOG_BODIES" -> "false",
      "LOG_BODY_MAX_BYTES" -> "2048",
      "LOG_LEVEL" -> "debug",
      "USER_AGENT_OVERRIDE" -> "false",
      "SERVICE_NAME" -> "gost",
      "ENV" -> "PROD",
      "CI_COMMIT" -> "deadbeef",
      "CI_REF" -> "main"
    ).fold(problem => fail(problem), identity)
    assertEquals(config.port, 9000)
    assertEquals(config.upstreamHost, "stunnel")
    assertEquals(config.upstreamPort, 9443)
    assertEquals(config.proxyToken, Some("abc"))
    assertEquals(config.logBodies, false)
    assertEquals(config.logBodyMaxBytes, 2048L)
    assertEquals(config.logLevel, LogLevel.Debug)
    assertEquals(config.userAgentOverride, false)
    assertEquals(config.serviceName, "gost")
    assertEquals(config.env, "prod")
    assertEquals(config.ciCommit, Some("deadbeef"))
    assertEquals(config.ciRef, Some("main"))
  }

  test("rejects malformed numbers and booleans") {
    assert(parse("TARGET_URL" -> "https://a.example", "PORT" -> "http").isLeft)
    assert(parse("TARGET_URL" -> "https://a.example", "PORT" -> "70000").isLeft)
    assert(parse("TARGET_URL" -> "https://a.example", "UPSTREAM_PORT" -> "-1").isLeft)
    assert(parse("TARGET_URL" -> "https://a.example", "LOG_BODIES" -> "maybe").isLeft)
    assert(parse("TARGET_URL" -> "https://a.example", "USER_AGENT_OVERRIDE" -> "maybe").isLeft)
  }

  test("accepts the documented boolean spellings for USER_AGENT_OVERRIDE") {
    List("true", "1", "yes", "on").foreach { value =>
      assertEquals(
        parse("TARGET_URL" -> "https://a.example", "USER_AGENT_OVERRIDE" -> value).map(_.userAgentOverride),
        Right(true)
      )
    }
    List("false", "0", "no", "off").foreach { value =>
      assertEquals(
        parse("TARGET_URL" -> "https://a.example", "USER_AGENT_OVERRIDE" -> value).map(_.userAgentOverride),
        Right(false)
      )
    }
  }

  test("a whitespace-only PROXY_TOKEN fails startup, an absent one means no token required") {
    assert(parse("TARGET_URL" -> "https://a.example", "PROXY_TOKEN" -> "   ").isLeft)
    assertEquals(
      parse("TARGET_URL" -> "https://a.example").map(_.proxyToken),
      Right(None)
    )
  }

  test("never renders the proxy token in the startup config") {
    val rendered = parse("TARGET_URL" -> "https://a.example", "PROXY_TOKEN" -> "s3cret")
      .fold(problem => fail(problem), _.nonSecret.noSpaces)
    assert(!rendered.contains("s3cret"))
    assert(rendered.contains("\"proxy_token\":true"))
    assert(rendered.contains("\"user_agent_override\":true"))
    assert(rendered.contains("\"log_level\":\"INFO\""))
  }

  test("LOG_LEVEL accepts DEBUG, INFO, WARNING, ERROR case-insensitively, and WARN as an alias for WARNING") {
    List(
      "debug" -> LogLevel.Debug,
      "DEBUG" -> LogLevel.Debug,
      "info" -> LogLevel.Info,
      "Info" -> LogLevel.Info,
      "warning" -> LogLevel.Warning,
      "WARNING" -> LogLevel.Warning,
      "warn" -> LogLevel.Warning,
      "WARN" -> LogLevel.Warning,
      "error" -> LogLevel.Error,
      "ERROR" -> LogLevel.Error
    ).foreach { (raw, expected) =>
      assertEquals(
        parse("TARGET_URL" -> "https://a.example", "LOG_LEVEL" -> raw).map(_.logLevel),
        Right(expected),
        raw
      )
    }
  }

  test("defaults LOG_LEVEL to INFO and rejects an unknown value") {
    assertEquals(parse("TARGET_URL" -> "https://a.example").map(_.logLevel), Right(LogLevel.Info))
    assert(parse("TARGET_URL" -> "https://a.example", "LOG_LEVEL" -> "verbose").isLeft)
  }

  test("LOG_BODY_MAX_BYTES defaults to 10 MiB") {
    assertEquals(parse("TARGET_URL" -> "https://a.example").map(_.logBodyMaxBytes), Right(10485760L))
  }

  test("LOG_BODY_MAX_BYTES accepts an explicit value") {
    assertEquals(
      parse("TARGET_URL" -> "https://a.example", "LOG_BODY_MAX_BYTES" -> "2048").map(_.logBodyMaxBytes),
      Right(2048L)
    )
  }

  test("LOG_BODY_MAX_BYTES accepts 0") {
    assertEquals(
      parse("TARGET_URL" -> "https://a.example", "LOG_BODY_MAX_BYTES" -> "0").map(_.logBodyMaxBytes),
      Right(0L)
    )
  }

  test("LOG_BODY_MAX_BYTES rejects a negative value") {
    assertEquals(
      parse("TARGET_URL" -> "https://a.example", "LOG_BODY_MAX_BYTES" -> "-1"),
      Left("LOG_BODY_MAX_BYTES must be a non-negative number of bytes, got '-1'")
    )
  }

  test("LOG_BODY_MAX_BYTES rejects a non-numeric value") {
    assertEquals(
      parse("TARGET_URL" -> "https://a.example", "LOG_BODY_MAX_BYTES" -> "10MB"),
      Left("LOG_BODY_MAX_BYTES must be a non-negative number of bytes, got '10MB'")
    )
  }

  test("LOG_BODY_MAX_BYTES rejects a value that does not fit a Long") {
    assertEquals(
      parse("TARGET_URL" -> "https://a.example", "LOG_BODY_MAX_BYTES" -> "99999999999999999999999999"),
      Left("LOG_BODY_MAX_BYTES must be a non-negative number of bytes, got '99999999999999999999999999'")
    )
  }
