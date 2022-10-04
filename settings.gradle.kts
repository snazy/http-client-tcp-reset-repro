if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_11)) {
  throw GradleException("Build requires Java 11")
}

pluginManagement {
  repositories {
    gradlePluginPortal()
  }
}

rootProject.name = "jdk-http-client-repro"

include("fixtures")
include("jetty-9")
include("jetty-11")
include("jdk-http-server")
