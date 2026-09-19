package proxy

import munit.FunSuite

final class ProcNetSuite extends FunSuite:
  private val Header =
    "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"
  private val Header6 =
    "  sl  local_address                         remote_address                        st tx_queue " +
      "rx_queue tr tm->when retrnsmt   uid  timeout inode"

  test("finds a LISTEN socket on the requested port") {
    val table = List(
      Header,
      "   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0 100 0 0 10 0"
    ).mkString("\n")
    assert(ProcNetTcp.hasListener(table, 8080))
  }

  test("an ESTABLISHED socket on the same local port does not count") {
    val table = List(
      Header,
      "   1: 0100007F:1F90 0100007F:9C4C 01 00000000:00000000 00:00000000 00000000     0        0 12346 1 0 20 4 30 10 -1"
    ).mkString("\n")
    assert(!ProcNetTcp.hasListener(table, 8080))
  }

  test("a LISTEN socket on a different port does not count") {
    val table = List(
      Header,
      "   0: 0100007F:2382 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0 100 0 0 10 0"
    ).mkString("\n")
    assert(!ProcNetTcp.hasListener(table, 8080))
  }

  // 443 is 01BB in hex: three significant digits, zero-padded to four in the kernel's fixed-width column
  test("finds a LISTEN socket whose port hex is zero-padded below four digits") {
    val table = List(
      Header,
      "   0: 0100007F:01BB 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0 100 0 0 10 0"
    ).mkString("\n")
    assert(ProcNetTcp.hasListener(table, 443))
  }

  test("a LISTEN socket on a different port does not count as the zero-padded port") {
    val table = List(
      Header,
      "   0: 0100007F:2382 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0 100 0 0 10 0"
    ).mkString("\n")
    assert(!ProcNetTcp.hasListener(table, 443))
  }

  test("a LISTEN row is not matched via the remote-address column") {
    val table = List(
      Header,
      "   0: 0100007F:2382 0100007F:1F90 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0 100 0 0 10 0"
    ).mkString("\n")
    assert(!ProcNetTcp.hasListener(table, 8080))
  }

  test("finds a LISTEN socket in the /proc/net/tcp6 column layout") {
    val table = List(
      Header6,
      "   0: 00000000000000000000000000000000:1F90 00000000000000000000000000000000:0000 0A " +
        "00000000:00000000 00:00000000 00000000     0        0 12345 1 0 100 0 0 10 0"
    ).mkString("\n")
    assert(ProcNetTcp.hasListener(table, 8080))
  }

  test("a malformed line is ignored rather than throwing") {
    val table = List(Header, "not a proc/net/tcp line at all", "").mkString("\n")
    assert(!ProcNetTcp.hasListener(table, 8080))
  }
