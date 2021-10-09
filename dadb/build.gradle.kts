import org.gradle.internal.jvm.inspection.DefaultJvmMetadataDetector
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.toolchain.internal.DefaultToolchainSpec
import org.gradle.jvm.toolchain.internal.JavaToolchainFactory
import org.gradle.jvm.toolchain.internal.JavaToolchainInput

plugins {
    `maven-publish`
    id("org.jetbrains.kotlin.jvm")
    id("com.vanniktech.maven.publish")
    `java-library`
    id("com.palantir.graal") version "0.9.0"
    id("org.graalvm.buildtools.native") version "0.9.5"
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.squareup.okio:okio:2.10.0")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("com.google.truth:truth:1.0.1")
}

val toolchainFactory = objects.newInstance(JavaToolchainFactory::class.java)
val metadataDetector = objects.newInstance(DefaultJvmMetadataDetector::class.java)

graal {
    graalVersion("21.0.0.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.nativeTestCompile {
    dependsOn(tasks.extractGraalTooling)
}

graalvmNative {
    binaries {
        all {
            buildArgs.add("-H:+EnableAllSecurityServices")
            javaLauncher.set(provider {
                val output = tasks.extractGraalTooling.get().outputs.files.singleFile
                val javaHome = if (OperatingSystem.current().isMacOsX) output.resolve("Contents/Home") else output
                val metadata = metadataDetector.getMetadata(javaHome)
                if (!metadata.isValidInstallation) {
                    throw RuntimeException(metadata.errorMessage)
                }
                val spec = objects.newInstance(DefaultToolchainSpec::class.java).apply {
                    languageVersion.set(JavaLanguageVersion.of(java.targetCompatibility.majorVersion))
                }
                val javaToolchain = toolchainFactory.newInstance(javaHome, JavaToolchainInput(spec))
                javaToolchain.get().javaLauncher
            })
        }
    }
}
