package proxy

import cats.effect.IO

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

// pure parser for the /proc/net/tcp and /proc/net/tcp6 table format: whitespace-padded columns,
// local_address is HEX_IP:HEX_PORT, and st 0A means LISTEN (01 ESTABLISHED and everything else does not)
object ProcNetTcp:
  private val Listen = "0A"

  def hasListener(table: String, port: Int): Boolean =
    val hexPort = f"$port%04x"
    table.linesIterator.exists(matches(_, hexPort))

  private def matches(line: String, hexPort: String): Boolean =
    line.trim.split("\\s+") match
      case Array(_, localAddress, _, state, _*) =>
        state.equalsIgnoreCase(Listen) && localAddress.split(":").lastOption.exists(_.equalsIgnoreCase(hexPort))
      case _ => false

// the table text behind the parser, injectable so tests can feed fixtures on a host without /proc
final case class ProcNetSource(tcp: IO[Option[String]], tcp6: IO[Option[String]]):
  // ponytail: lives on the instance, not a bare static, so it tracks the process lifetime in prod (one shared
  // default val) while each test's own fixture instance still gets a clean flag
  private[proxy] val unavailableWarned = new AtomicBoolean(false)

object ProcNetSource:
  val default: ProcNetSource = ProcNetSource(read("/proc/net/tcp"), read("/proc/net/tcp6"))

  // None covers a missing /proc (macOS, most non-Linux hosts) and a permission failure alike
  private def read(path: String): IO[Option[String]] =
    IO.blocking(Try(Files.readString(Paths.get(path))).toOption)
