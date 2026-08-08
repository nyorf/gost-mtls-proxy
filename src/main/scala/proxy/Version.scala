package proxy

object Version:
  // Implementation-Version comes from the jar manifest (build.sbt writes version.value there);
  // running from classes, as tests do, there is no manifest, hence the fallback
  val current: String =
    Option(getClass.getPackage).flatMap(p => Option(p.getImplementationVersion)).getOrElse("dev")

  val userAgent: String = s"GostMtlsProxy/v$current"
