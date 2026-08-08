package proxy

import cats.effect.IO
import io.circe.Json

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference

final case class ReqCtx(requestId: String, traceId: String, spanId: String, method: String, route: String):
  def fields: List[(String, Json)] = List(
    "request_id" -> Json.fromString(requestId),
    "trace-id" -> Json.fromString(traceId),
    "span-id" -> Json.fromString(spanId),
    "method" -> Json.fromString(method),
    "route" -> Json.fromString(route)
  )

final class JsonLog(base: List[(String, Json)], sink: String => Unit, now: () => Instant, threshold: LogLevel):
  def debug(logger: String, message: String, ctx: Option[ReqCtx], fields: (String, Json)*): IO[Unit] =
    IO(write(LogLevel.Debug, logger, message, ctx, fields))

  def info(logger: String, message: String, ctx: Option[ReqCtx], fields: (String, Json)*): IO[Unit] =
    IO(write(LogLevel.Info, logger, message, ctx, fields))

  def warn(logger: String, message: String, ctx: Option[ReqCtx], fields: (String, Json)*): IO[Unit] =
    IO(write(LogLevel.Warning, logger, message, ctx, fields))

  def error(logger: String, message: String, ctx: Option[ReqCtx], fields: (String, Json)*): IO[Unit] =
    IO(write(LogLevel.Error, logger, message, ctx, fields))

  // consulted by the slf4j bridge's isXxxEnabled instead of a hardcoded answer
  def isEnabled(level: LogLevel): Boolean = threshold.allows(level)

  // synchronous entry point: slf4j has no effect type and calls this from library threads
  def write(level: LogLevel, logger: String, message: String, ctx: Option[ReqCtx], fields: Seq[(String, Json)]): Unit =
    if isEnabled(level) then
      val head = List(
        "message" -> Json.fromString(message),
        "level" -> Json.fromString(level.name),
        "@timestamp" -> Json.fromString(JsonLog.isoOffset(now())),
        "logger" -> Json.fromString(logger)
      )
      sink(Json.fromFields(head ++ base ++ ctx.toList.flatMap(_.fields) ++ fields).noSpaces)

object JsonLog:
  private val offsetFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx").withZone(ZoneOffset.UTC)
  private val zuluFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

  def isoOffset(at: Instant): String = offsetFormat.format(at)
  def isoZ(at: Instant): String = zuluFormat.format(at)

  def apply(
      config: Config,
      deployedAt: Instant,
      hostname: String,
      sink: String => Unit,
      now: () => Instant = () => Instant.now()
  ): JsonLog =
    val ci = Json.fromFields(
      List("deployed_at" -> Json.fromString(isoOffset(deployedAt))) ++
        config.ciCommit.map(c => "commit" -> Json.fromString(c)) ++
        config.ciRef.map(r => "ref" -> Json.fromString(r))
    )
    val base = List(
      "system" -> Json.fromString(config.serviceName),
      "env" -> Json.fromString(config.env.toLowerCase),
      "inst" -> Json.fromString(hostname),
      "ci" -> ci
    )
    new JsonLog(base, sink, now, config.logLevel)

  private val current: AtomicReference[Option[JsonLog]] = new AtomicReference(None)

  def install(log: JsonLog): Unit = current.set(Some(log))
  def installed: Option[JsonLog] = current.get()

  def hostname(env: Map[String, String]): String =
    env
      .get("HOSTNAME")
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(scala.util.Try(java.net.InetAddress.getLocalHost.getHostName).toOption.filter(_.nonEmpty))
      .getOrElse("unknown")
