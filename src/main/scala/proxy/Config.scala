package proxy

import io.circe.Json
import org.http4s.Uri

final case class Config(
    targetAuthority: String,
    port: Int,
    upstreamHost: String,
    upstreamPort: Int,
    proxyToken: Option[String],
    logBodies: Boolean,
    logBodyMaxBytes: Long,
    logLevel: LogLevel,
    userAgentOverride: Boolean,
    serviceName: String,
    env: String,
    ciCommit: Option[String],
    ciRef: Option[String]
):
  def targetUrl: String = s"https://$targetAuthority"

  def nonSecret: Json = Json.obj(
    "target_url" -> Json.fromString(targetUrl),
    "port" -> Json.fromInt(port),
    "upstream_host" -> Json.fromString(upstreamHost),
    "upstream_port" -> Json.fromInt(upstreamPort),
    "proxy_token" -> Json.fromBoolean(proxyToken.isDefined),
    "log_bodies" -> Json.fromBoolean(logBodies),
    "log_body_max_bytes" -> Json.fromLong(logBodyMaxBytes),
    "log_level" -> Json.fromString(logLevel.name),
    "user_agent_override" -> Json.fromBoolean(userAgentOverride),
    "service_name" -> Json.fromString(serviceName),
    "env" -> Json.fromString(env),
    "ci_commit" -> ciCommit.fold(Json.Null)(Json.fromString),
    "ci_ref" -> ciRef.fold(Json.Null)(Json.fromString)
  )

object Config:
  def from(env: Map[String, String]): Either[String, Config] =
    for
      target <- required(env, "TARGET_URL").flatMap(authority)
      port <- portVar(env, "PORT", 8080)
      upstreamHost <- Right(str(env, "UPSTREAM_HOST").getOrElse("127.0.0.1"))
      upstreamPort <- portVar(env, "UPSTREAM_PORT", 8443)
      logBodies <- boolVar(env, "LOG_BODIES", true)
      logBodyMaxBytes <- longVar(env, "LOG_BODY_MAX_BYTES", 10485760L)
      logLevel <- logLevelVar(env, "LOG_LEVEL")
      userAgentOverride <- boolVar(env, "USER_AGENT_OVERRIDE", true)
      proxyToken <- tokenVar(env, "PROXY_TOKEN")
    yield Config(
      targetAuthority = target,
      port = port,
      upstreamHost = upstreamHost,
      upstreamPort = upstreamPort,
      proxyToken = proxyToken,
      logBodies = logBodies,
      logBodyMaxBytes = logBodyMaxBytes,
      logLevel = logLevel,
      userAgentOverride = userAgentOverride,
      serviceName = str(env, "SERVICE_NAME").getOrElse("gost-mtls-proxy"),
      env = str(env, "ENV").getOrElse("dev").toLowerCase,
      ciCommit = str(env, "CI_COMMIT"),
      ciRef = str(env, "CI_REF")
    )

  private def str(env: Map[String, String], name: String): Option[String] =
    env.get(name).map(_.trim).filter(_.nonEmpty)

  private def required(env: Map[String, String], name: String): Either[String, String] =
    str(env, name).toRight(s"$name is required")

  private def portVar(env: Map[String, String], name: String, default: Int): Either[String, Int] =
    str(env, name) match
      case None      => Right(default)
      case Some(raw) =>
        raw.toIntOption
          .filter(p => p >= 0 && p <= 65535)
          .toRight(s"$name must be a port number between 0 and 65535, got '$raw'")

  private def tokenVar(env: Map[String, String], name: String): Either[String, Option[String]] =
    env.get(name) match
      case None      => Right(None)
      case Some(raw) =>
        val trimmed = raw.trim
        // absent means "no token required"; present-but-blank is a config mistake, not the same thing
        Either.cond(trimmed.nonEmpty, Some(trimmed), s"$name is set but blank")

  private def logLevelVar(env: Map[String, String], name: String): Either[String, LogLevel] =
    str(env, name).fold(Right(LogLevel.Info): Either[String, LogLevel])(LogLevel.parse)

  private def longVar(env: Map[String, String], name: String, default: Long): Either[String, Long] =
    str(env, name) match
      case None      => Right(default)
      case Some(raw) =>
        raw.toLongOption
          .filter(_ >= 0)
          .toRight(s"$name must be a non-negative number of bytes, got '$raw'")

  private def boolVar(env: Map[String, String], name: String, default: Boolean): Either[String, Boolean] =
    str(env, name) match
      case None      => Right(default)
      case Some(raw) =>
        raw.toLowerCase match
          case "true" | "1" | "yes" | "on"  => Right(true)
          case "false" | "0" | "no" | "off" => Right(false)
          case other                        => Left(s"$name must be a boolean, got '$other'")

  private def authority(raw: String): Either[String, String] =
    Uri
      .fromString(raw)
      .left
      .map(failure => s"TARGET_URL is not a valid URL: ${failure.sanitized}")
      .flatMap: uri =>
        for
          _ <- Either.cond(uri.scheme.contains(Uri.Scheme.https), (), "TARGET_URL must use the https scheme")
          auth <- uri.authority.toRight("TARGET_URL must contain a host")
          _ <- Either.cond(auth.userInfo.isEmpty, (), "TARGET_URL must not contain user info")
          path = uri.path.renderString
          _ <- Either.cond(path.isEmpty || path == "/", (), s"TARGET_URL must not contain a path, got '$path'")
          _ <- Either.cond(uri.query.renderString.isEmpty, (), "TARGET_URL must not contain a query")
          _ <- Either.cond(uri.fragment.isEmpty, (), "TARGET_URL must not contain a fragment")
        yield auth.renderString
