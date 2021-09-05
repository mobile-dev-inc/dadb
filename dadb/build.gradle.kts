plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core"))
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}
