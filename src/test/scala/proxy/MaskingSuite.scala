package proxy

import io.circe.parser
import munit.FunSuite

final class MaskingSuite extends FunSuite:
  private def mask(raw: String): io.circe.HCursor =
    Masking.maskJson(parser.parse(raw).fold(throw _, identity)).hcursor

  test("masks header values case-insensitively and leaves the rest alone") {
    val lines = Masking.headerLines(
      List(
        "AUTHORIZATION" -> "Bearer x",
        "cookie" -> "s=1",
        "Set-Cookie" -> "s=1",
        "X-Api-Key" -> "k",
        "x-proxy-token" -> "t",
        "Proxy-Authorization" -> "Basic zzz",
        "Accept" -> "*/*"
      )
    )
    assertEquals(
      lines,
      List(
        "AUTHORIZATION: ***",
        "cookie: ***",
        "Set-Cookie: ***",
        "X-Api-Key: ***",
        "x-proxy-token: ***",
        "Proxy-Authorization: ***",
        "Accept: */*"
      )
    )
  }

  test("masks recursively through objects and arrays") {
    val masked = mask(
      """{"a":{"token":"x","Email":"ivan@example.com"},"list":[{"password":"p"}],"keep":"as is"}"""
    )
    assertEquals(masked.downField("a").get[String]("token"), Right("***"))
    assertEquals(masked.downField("a").get[String]("Email"), Right("ivan@example.com"))
    assertEquals(masked.downField("list").downN(0).get[String]("password"), Right("***"))
    assertEquals(masked.get[String]("keep"), Right("as is"))
  }

  test("clips only what exceeds the limit") {
    assertEquals(Masking.clip("a" * 12, 10), ("a" * 10) + "… (truncated)")
    assertEquals(Masking.clip("short", 10), "short")
  }

  test("never splits a surrogate pair when the limit lands inside an emoji") {
    val emoji = "😀" // one codepoint, two UTF-16 units
    val value = ("a" * 9) + emoji + ("b" * 5)
    val clipped = Masking.clip(value, 10)
    assertEquals(clipped, ("a" * 9) + "… (truncated)")
    assert(!clipped.exists(Character.isSurrogate), s"lone surrogate leaked into: $clipped")
  }
