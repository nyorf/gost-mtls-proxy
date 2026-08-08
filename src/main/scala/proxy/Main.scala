package proxy

import cats.effect.{ExitCode, IO, IOApp}
import com.comcast.ip4s.{Port, ipv4}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    IO(sys.env).flatMap: env =>
      Config.from(env) match
        case Left(problem) => IO(System.err.println(s"invalid configuration: $problem")).as(ExitCode.Error)
        case Right(config) => serve(config, env).as(ExitCode.Success)

  private def serve(config: Config, env: Map[String, String]): IO[Nothing] =
    for
      startedAt <- IO.realTimeInstant
      log = JsonLog(config, startedAt, JsonLog.hostname(env), line => System.out.println(line))
      _ <- IO(JsonLog.install(log))
      _ <- log.info("app.operations", "startup config", None, "config" -> config.nonSecret)
      port <- IO.fromOption(Port.fromInt(config.port))(new IllegalArgumentException(s"bad port ${config.port}"))
      // the proxy owns the outgoing User-Agent entirely; ember's own default would otherwise sneak in unasked
      exit <- EmberClientBuilder
        .default[IO]
        .withoutUserAgent
        .build
        .flatMap: client =>
          EmberServerBuilder
            .default[IO]
            .withHost(ipv4"0.0.0.0")
            .withPort(port)
            .withHttpApp(Proxy.app(config, client, log))
            .build
        .useForever
    yield exit
