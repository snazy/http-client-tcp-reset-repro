plugins {
  `java-library`
  `project-conventions`
}

dependencies {
  implementation("org.apache.httpcomponents.client5:httpclient5:5.1.3")

  implementation(platform(libs.junit.bom))
  implementation(libs.bundles.junit.testing)
}
