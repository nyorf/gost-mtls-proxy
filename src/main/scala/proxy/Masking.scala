package proxy

import io.circe.{Json, JsonObject}

object Masking:
  val Masked: String = "***"

  // x-proxy-token is not in the ported list: it is this proxy's own shared secret and would otherwise be logged in full
  // proxy-authorization is stripped from the wire (hop-by-hop) but still appears in the inbound headers log
  private val maskedHeaders =
    Set("authorization", "cookie", "set-cookie", "x-api-key", "x-proxy-token", "proxy-authorization")

  private val fullKeys = Set("token", "raw_token", "rawtoken", "password", "secret", "apikey", "api_key")

  def maskHeaderValue(name: String, value: String): String =
    if maskedHeaders.contains(name.toLowerCase) then Masked else value

  def headerLines(headers: Iterable[(String, String)]): List[String] =
    headers.map((name, value) => s"$name: ${maskHeaderValue(name, value)}").toList

  def maskJson(json: Json): Json =
    json.arrayOrObject(
      json,
      values => Json.fromValues(values.map(maskJson)),
      obj =>
        Json.fromJsonObject(JsonObject.fromIterable(obj.toIterable.map((key, value) => key -> maskValue(key, value))))
    )

  def clip(value: String, max: Int): String =
    if value.length <= max then value
    else
      // take() counts UTF-16 units; back off one if that lands mid surrogate pair
      val boundary = if max > 0 && Character.isHighSurrogate(value.charAt(max - 1)) then max - 1 else max
      value.take(boundary) + "… (truncated)"

  private def maskValue(key: String, value: Json): Json =
    key.toLowerCase match
      case k if fullKeys(k) => Json.fromString(Masked)
      case _                => maskJson(value)
