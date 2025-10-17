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
    api("com.squareup.okio:okio:3.16.2")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("com.google.truth:truth:1.4.0")
}


tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(true)
}