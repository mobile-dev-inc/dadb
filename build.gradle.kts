import com.adarshr.gradle.testlogger.TestLoggerExtension
import com.adarshr.gradle.testlogger.theme.ThemeType.STANDARD
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(kotlin("gradle-plugin", "1.9.20"))
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.33.0")
    }
}

plugins {
    id("com.adarshr.test-logger") version("3.2.0") apply(true)
}

allprojects {
    tasks.withType(JavaCompile::class.java) {
        sourceCompatibility = JavaVersion.VERSION_11.toString()
        targetCompatibility = JavaVersion.VERSION_11.toString()
    }
    tasks.withType(KotlinCompile::class.java) {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_11.toString()
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
}
