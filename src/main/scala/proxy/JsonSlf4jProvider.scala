package proxy

import io.circe.Json
import org.slf4j.event.Level
import org.slf4j.helpers.{BasicMarkerFactory, LegacyAbstractLogger, MessageFormatter, NOPMDCAdapter}
import org.slf4j.spi.{MDCAdapter, SLF4JServiceProvider}
import org.slf4j.{ILoggerFactory, IMarkerFactory, Logger, Marker}

import java.util.concurrent.ConcurrentHashMap

// registered via META-INF/services so http4s/ember records render as json lines instead of slf4j's no-provider warning
final class JsonSlf4jProvider extends SLF4JServiceProvider:
  private val loggers = new ConcurrentHashMap[String, Logger]
  private val markerFactory = new BasicMarkerFactory
  private val mdcAdapter = new NOPMDCAdapter
  private val loggerFactory = new ILoggerFactory:
    def getLogger(name: String): Logger = loggers.computeIfAbsent(name, n => new JsonSlf4jLogger(n))

  override def getLoggerFactory: ILoggerFactory = loggerFactory
  override def getMarkerFactory: IMarkerFactory = markerFactory
  override def getMDCAdapter: MDCAdapter = mdcAdapter
  override def getRequestedApiVersion: String = "2.0.99"
  override def initialize(): Unit = ()

final class JsonSlf4jLogger(loggerName: String) extends LegacyAbstractLogger:
  override def getName: String = loggerName

  // no installed log yet (early startup) defaults match the old hardcoded behavior: info/warn/error on, debug/trace off
  override def isTraceEnabled: Boolean = false
  override def isDebugEnabled: Boolean = JsonLog.installed.exists(_.isEnabled(LogLevel.Debug))
  override def isInfoEnabled: Boolean = JsonLog.installed.forall(_.isEnabled(LogLevel.Info))
  override def isWarnEnabled: Boolean = JsonLog.installed.forall(_.isEnabled(LogLevel.Warning))
  override def isErrorEnabled: Boolean = JsonLog.installed.forall(_.isEnabled(LogLevel.Error))

  override def isTraceEnabled(marker: Marker): Boolean = isTraceEnabled
  override def isDebugEnabled(marker: Marker): Boolean = isDebugEnabled
  override def isInfoEnabled(marker: Marker): Boolean = isInfoEnabled
  override def isWarnEnabled(marker: Marker): Boolean = isWarnEnabled
  override def isErrorEnabled(marker: Marker): Boolean = isErrorEnabled

  override protected def getFullyQualifiedCallerName: String = classOf[JsonSlf4jLogger].getName

  override protected def handleNormalizedLoggingCall(
      level: Level,
      marker: Marker,
      messagePattern: String,
      arguments: Array[AnyRef],
      throwable: Throwable
  ): Unit =
    JsonLog.installed.foreach: log =>
      val error = Option(throwable).map(t => "error" -> Json.fromString(s"${t.getClass.getName}: ${t.getMessage}"))
      log.write(
        JsonSlf4jLogger.levelName(level),
        loggerName,
        MessageFormatter.basicArrayFormat(messagePattern, arguments),
        None,
        error.toList
      )

object JsonSlf4jLogger:
  def levelName(level: Level): LogLevel = level match
    case Level.ERROR => LogLevel.Error
    case Level.WARN  => LogLevel.Warning
    case Level.DEBUG => LogLevel.Debug
    case Level.TRACE => LogLevel.Debug // this contract has no trace level; debug is the closest fit
    case _           => LogLevel.Info
