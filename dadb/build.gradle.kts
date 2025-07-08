import org.gradle.internal.jvm.inspection.DefaultJvmMetadataDetector

plugins {
    `maven-publish`
    id("org.jetbrains.kotlin.jvm")
    id("com.vanniktech.maven.publish")
    `java-library`
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

val metadataDetector = objects.newInstance(DefaultJvmMetadataDetector::class.java)

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(true)
}