plugins {
  `java-library`
  `project-conventions`
}

dependencies {
  testImplementation(project(":fixtures"))

  testImplementation(platform("org.eclipse.jetty:jetty-bom:9.4.49.v20220914"))
  testImplementation("org.eclipse.jetty.http2:http2-server")

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.bundles.junit.testing)
  testRuntimeOnly(libs.junit.jupiter.engine)
}
