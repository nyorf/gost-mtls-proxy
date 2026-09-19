package proxy

import cats.effect.{IO, Ref, Resource}
import com.comcast.ip4s.{ipv4, port}
import io.circe.{Json, parser}
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

final case class Captured(method: Method, uri: Uri, headers: Headers, body: Array[Byte]):
  def text: String = new String(body, UTF_8)
  def header(name: String): Option[String] = headers.get(CIString(name)).map(_.head.value)
  def all(name: String): List[String] = headers.get(CIString(name)).toList.flatMap(_.toList).map(_.value)

final case class Result(status: Status, headers: Headers, body: Array[Byte]):
  def text: String = new String(body, UTF_8)
  def header(name: String): Option[String] = headers.get(CIString(name)).map(_.head.value)

final class LogLines:
  private val queue = new ConcurrentLinkedQueue[String]()
  val sink: String => Unit = line => queue.add(line)

  def raw: List[String] = queue.asScala.toList

  def parsed: List[Json] =
    raw.map(line => parser.parse(line).fold(error => throw new AssertionError(s"not json: $line", error), identity))

  def withMessage(message: String): List[Json] =
    parsed.filter(_.hcursor.get[String]("message").contains(message))

object TestSupport:
  val targetAuthority: String = "target.example:443"

  def upstream(app: HttpApp[IO]): Resource[IO, Int] =
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"127.0.0.1")
      .withPort(port"0")
      .withHttpApp(app)
      .build
      .map(_.address.getPort)

  def capturing(seen: Ref[IO, Option[Captured]], respond: Response[IO] = Response[IO](Status.Ok)): HttpApp[IO] =
    HttpApp[IO]: request =>
      request.body.compile
        .to(Array)
        .flatMap(body => seen.set(Some(Captured(request.method, request.uri, request.headers, body))))
        .as(respond)

  def config(upstreamPort: Int, extra: (String, String)*): Config =
    Config
      .from(
        Map(
          "TARGET_URL" -> s"https://$targetAuthority",
          "UPSTREAM_HOST" -> "127.0.0.1",
          "UPSTREAM_PORT" -> upstreamPort.toString
        ) ++ extra
      )
      .fold(problem => throw new AssertionError(problem), identity)

  def logger(config: Config, lines: LogLines): JsonLog =
    JsonLog(config, Instant.parse("2026-01-01T00:00:00Z"), "test-host", lines.sink)

  def silent(config: Config): JsonLog = logger(config, new LogLines)

  // drives the proxy directly so tests own the exact inbound headers; the upstream hop is a real ember client
  def send(
      config: Config,
      log: JsonLog,
      request: Request[IO],
      procNet: ProcNetSource = ProcNetSource.default
  ): IO[Result] =
    EmberClientBuilder
      .default[IO]
      .withoutUserAgent
      .build
      .use: client =>
        Proxy
          .app(config, client, log, procNet)
          .run(request)
          .flatMap(response => response.body.compile.to(Array).map(Result(response.status, response.headers, _)))

  // a port nothing listens on: bound to learn a free one, then released
  val closedPort: IO[Int] = upstream(HttpApp.notFound[IO]).use(IO.pure)

  // a fabricated /proc/net/tcp table reporting the given port as LISTEN, for tests that must not depend on a real kernel table
  def listeningProcNet(port: Int): ProcNetSource =
    val row = f"   0: 0100007F:$port%04X 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1 1 0 0 0 0 0"
    ProcNetSource(IO.pure(Some(row)), IO.pure(None))

  val unavailableProcNet: ProcNetSource = ProcNetSource(IO.pure(None), IO.pure(None))
