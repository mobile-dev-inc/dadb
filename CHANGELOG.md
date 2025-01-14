# Changelog

### 1.2.9

* Various fixes for Windows

### 1.2.8

* Allow streaming apk install
* Add timeout option when create Dadb

### 1.2.7

* Fix for install on physical devices

### 1.2.6

* When generating adb keys, ensure that parent directories are created if needed

### 1.2.5

* Fix: reverted back refactoring changes from 1.2.1

### 1.2.4

* Fix slow installation in some cases

### 1.2.3

* Fix for AdbSync refactor in 1.2.2

### 1.2.2

* `AdbServerDadb.create` renamed to `AdbServer.createDadb`
* `Dadb.discover` now supports physical devices (via the adb server)
* Added `Dadb.list()`
* Added default host parameter for `Dadb.discover()`
* Added `AdbServer.listDadbs` (no need to use this directly, use `Dadb.list()`)

### 1.2.1

* Fix push for API 24

### 1.2.0

* Add experimental API AdbServerDadb.create to connect to an adb server

### 1.1.0

* Fix unicode handling
* Add Dadb.installMultiple

### 1.0.0

* Fix: adbkey.pub is no longer required
* Updating version to 1.0.0 to be semantically aligned with other mobile.dev libraries

### 0.0.13

* Fix bad release

### 0.0.12

* Make `AdbShellPacket` a sealed class

### 0.0.11

* `Dadb.create` / `Dadb.create` / `Dadb.discover` / `AdbKey.readDefault()` will all generate a key if one is not found in the default location.

### 0.0.10

* Add support for TCP port forwarding

### 0.0.9

* Fix `Dadb.pull` for large files

### 0.0.8

* `Dadb.install` fixed for Android SDK version <= 28
* `Dadb.create` / `Dadb.discover` uses `~/.android/adbkey` by default
