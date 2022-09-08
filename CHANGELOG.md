# Changelog

### 1.1.1-SNAPSHOT

* 

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
