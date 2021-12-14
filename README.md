# dadb

[![Maven Central](https://img.shields.io/maven-central/v/dev.mobile/dadb.svg)](https://mvnrepository.com/artifact/dev.mobile/dadb)

[Blog Post: Our First Open-Source Project](https://blog.mobile.dev/our-first-open-source-project-54cd8edc452f)

<img src="https://user-images.githubusercontent.com/847683/143626125-5872bdd8-180e-48bb-a64f-47b3688a086d.png" width="700px" />

A Kotlin/Java library to connect directly to an Android device without an adb binary or an ADB server

```kotlin
dependencies {
  implementation("dev.mobile:dadb:<version>")
}
```

### Example Usage

Connect to `emulator-5554` and install `apkFile`:

```kotlin
Dadb.create("localhost", 5555).use { dadb ->
    dadb.install(apkFile)
}
```

*Note: Connect to the odd adb daemon port (5555), not the even emulator console port (5554)*

### Discover a Device

Searches `localhost` ports `5555` through `5683` for a valid adb device:  

```kotlin
val dadb = Dadb.discover("localhost")
if (dadb == null) throw RuntimeException("No adb device found")
```

### Install / Uninstall APK

```kotlin
dadb.install(exampleApkFile)
dadb.uninstall("com.example.app")
```

### Push / Pull Files

```kotlin
dadb.push(srcFile, "/data/local/tmp/dst.txt")
dadb.pull(dstFile, "/data/local/tmp/src.txt")
```

### Execute Shell Command

```kotlin
val response = dadb.shell("echo hello")
assert(response.exitCode == 0)
assert(response.output == "hello\n")
```

### Authentication

**Dadb will use your adb key at `~/.android/adbkey` by default.**

If you need to specify a custom path to your adb key, use the optional `keyPair` argument:

```kotlin
val adbKeyPair = AdbKeyPair.create(privateKeyFile, publicKeyFile)
Dadb.create("localhost", 5555, adbKeyPair)
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
