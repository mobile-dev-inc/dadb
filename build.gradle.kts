import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(kotlin("gradle-plugin", "1.5.30"))
    }
}

allprojects {
    tasks.withType(KotlinCompile::class.java) {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs += "-Xopt-in=kotlin.ExperimentalUnsignedTypes"
        }
    }
}
