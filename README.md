# dadb

A Kotlin/Java library to connect directly to an Android device without an adb binary or a running ADB server

## Gradle dependency

[![Maven Central](https://img.shields.io/maven-central/v/dev.mobile/dadb.svg)](https://mvnrepository.com/artifact/dev.mobile/dadb)
```kotlin
dependencies {
  implementation("dev.mobile:dadb:<version>")
}
```

## Example Usage

Connect to `emulator-5554` and install `apkFile`:

```kotlin
Dadb.create("localhost", 5555).use { dadb ->
    dadb.install(apkFile)
}
```

*Note: Connect to the odd adb daemon port (5555), not the even emulator console port (5554)*

## Discover a Device

Searches `localhost` ports `5555` through `5683` for a valid adb device:  

```kotlin
val dadb = Dadb.discover("localhost")
if (dadb == null) throw RuntimeException("No adb device found")
```

## Install / Uninstall APK

```kotlin
dadb.install(exampleApkFile)
dadb.uninstall("com.example.app")
```

## Push / Pull Files

```kotlin
dadb.push(srcFile, "/data/local/tmp/dst.txt")
dadb.pull(dstFile, "/data/local/tmp/src.txt")
```

## Execute Shell Command

```kotlin
val response = dadb.shell("echo hello")
assert(response.exitCode == 0)
assert(response.output == "hello\n")
```
