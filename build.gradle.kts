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

allprojects {
    tasks.withType(KotlinCompile::class.java) {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs += "-Xopt-in=kotlin.ExperimentalUnsignedTypes"
        }
    }
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.getByType(MavenPublishPluginExtension::class.java).apply {
            sonatypeHost = SonatypeHost.S01
        }
    }
}
