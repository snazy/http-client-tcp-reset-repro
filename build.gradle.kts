import org.jetbrains.gradle.ext.delegateActions
import org.jetbrains.gradle.ext.encodings
import org.jetbrains.gradle.ext.settings

plugins {
  alias(libs.plugins.idea.ext)
}

tasks.named<Wrapper>("wrapper") { distributionType = Wrapper.DistributionType.ALL }

idea {
  project.settings {
    encodings.encoding = "UTF-8"
    encodings.properties.encoding = "UTF-8"
    delegateActions.testRunner =
      org.jetbrains.gradle.ext.ActionDelegationConfig.TestRunner.CHOOSE_PER_TEST
  }
}
