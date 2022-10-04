import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.repositories

configureJava()

testTasks()

fun Project.testTasks() {
  tasks.withType<Test>().configureEach {
    useJUnitPlatform {}
    val testJvmArgs: String? by project
    if (testJvmArgs != null) {
      jvmArgs((testJvmArgs as String).split(" "))
    }

    setMinHeapSize("1g")
    setMaxHeapSize("1g")

    systemProperty("file.encoding", "UTF-8")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("user.variant", "")
  }
}

fun Project.configureJava() {
  repositories {
    mavenCentral()
  }

  tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
  }

  plugins.withType<JavaPlugin>().configureEach {
    configure<JavaPluginExtension> {
      sourceCompatibility = JavaVersion.VERSION_11
      targetCompatibility = JavaVersion.VERSION_11
    }
  }
}
