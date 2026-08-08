package proxy

// declaration order is severity order: ordinal comparison is the threshold check
enum LogLevel:
  case Debug, Info, Warning, Error

  def name: String = this match
    case Debug   => "DEBUG"
    case Info    => "INFO"
    case Warning => "WARNING"
    case Error   => "ERROR"

  def allows(level: LogLevel): Boolean = level.ordinal >= ordinal

object LogLevel:
  def parse(raw: String): Either[String, LogLevel] =
    raw.trim.toUpperCase match
      case "DEBUG"            => Right(Debug)
      case "INFO"             => Right(Info)
      case "WARNING" | "WARN" => Right(Warning) // WARN is the alias this contract accepts for WARNING
      case "ERROR"            => Right(Error)
      case other => Left(s"LOG_LEVEL must be one of DEBUG, INFO, WARNING, ERROR (WARN also accepted), got '$other'")
