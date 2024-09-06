import com.adarshr.gradle.testlogger.TestLoggerExtension
import com.adarshr.gradle.testlogger.theme.ThemeType.STANDARD
import com.vanniktech.maven.publish.MavenPublishPluginExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.vanniktech.maven.publish.SonatypeHost

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(kotlin("gradle-plugin", "1.5.30"))
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.17.0")
    }
}

plugins {
    id("com.adarshr.test-logger") version("3.2.0") apply(true)
}

allprojects {
    tasks.withType(JavaCompile::class.java) {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
    tasks.withType(KotlinCompile::class.java) {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs += "-Xopt-in=kotlin.ExperimentalUnsignedTypes"
        }
    }
    tasks.withType(Task::class) {
        project.apply(plugin = "com.adarshr.test-logger")
        project.configure<TestLoggerExtension> {
            theme = STANDARD
            showExceptions = true
            showStackTraces = false
            showFullStackTraces = false
            showCauses = true
            slowThreshold = 5000
            showSummary = true
            showSimpleNames = false
            showPassed = true
            showSkipped = true
            showFailed = true
            showOnlySlow = false
            showStandardStreams = false
            showPassedStandardStreams = false
            showSkippedStandardStreams = false
            showFailedStandardStreams = true
        }
    }
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.getByType(MavenPublishPluginExtension::class.java).apply {
            sonatypeHost = SonatypeHost.S01
        }
    }
}
