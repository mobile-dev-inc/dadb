plugins {
    id("com.adarshr.test-logger") version ("3.2.0")
    id("org.jetbrains.kotlin.jvm") version ("1.8.22") apply false
    id("com.palantir.graal") version "0.9.0" apply false
    id("org.graalvm.buildtools.native") version "0.9.5" apply false
    id("com.vanniktech.maven.publish") version ("0.17.0") apply false
}
