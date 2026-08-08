package proxy

import munit.FunSuite
import org.slf4j.LoggerFactory

final class Slf4jBridgeSuite extends FunSuite:
  private val libraryLogger = "org.http4s.ember.server.EmberServerBuilder"

  test("slf4j resolves this provider and library records land in the json renderer") {
    val lines = new LogLines
    JsonLog.install(TestSupport.logger(TestSupport.config(8443), lines))
    val logger = LoggerFactory.getLogger(libraryLogger)
    assert(logger.isInstanceOf[JsonSlf4jLogger], s"slf4j picked ${logger.getClass.getName}")
    assertEquals(logger.getName, libraryLogger)

    logger.debug("dropped")
    logger.info("bound to {}:{}", "0.0.0.0", Integer.valueOf(8080))
    logger.error("boom", new IllegalStateException("kaput"))

    val emitted = lines.parsed.filter(_.hcursor.get[String]("logger").contains(libraryLogger))
    assertEquals(emitted.length, 2, lines.raw.toString)
    val info = emitted.head.hcursor
    assertEquals(info.get[String]("message"), Right("bound to 0.0.0.0:8080"))
    assertEquals(info.get[String]("level"), Right("INFO"))
    assertEquals(info.get[String]("system"), Right("gost-mtls-proxy"))
    val failure = emitted(1).hcursor
    assertEquals(failure.get[String]("level"), Right("ERROR"))
    assertEquals(failure.get[String]("error"), Right("java.lang.IllegalStateException: kaput"))
  }

  test("at LOG_LEVEL=DEBUG the bridge's isDebugEnabled flips and debug records actually emit as DEBUG") {
    val lines = new LogLines
    val cfg = TestSupport.config(8443, "LOG_LEVEL" -> "DEBUG")
    JsonLog.install(TestSupport.logger(cfg, lines))
    val logger = LoggerFactory.getLogger(libraryLogger)

    assert(logger.isDebugEnabled)
    logger.debug("now visible")

    val emitted = lines.parsed.filter(_.hcursor.get[String]("logger").contains(libraryLogger))
    assertEquals(emitted.length, 1, lines.raw.toString)
    assertEquals(emitted.head.hcursor.get[String]("message"), Right("now visible"))
    assertEquals(emitted.head.hcursor.get[String]("level"), Right("DEBUG"))
  }
