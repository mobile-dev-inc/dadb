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

# License

```
Copyright (c) 2021 mobile.dev inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
