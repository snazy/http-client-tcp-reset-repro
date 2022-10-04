plugins {
  `java-library`
  `project-conventions`
}

dependencies {
  testImplementation(project(":fixtures"))

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.bundles.junit.testing)
  testRuntimeOnly(libs.junit.jupiter.engine)
}
