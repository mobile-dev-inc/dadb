package dadb.devices

import com.sun.security.auth.module.UnixSystem
import dadb.devices.Emulator.Companion.toEmulator
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class DeviceFinder(
  val registrationDirectory: Path = findRegistrationDirectory(),
  val fileSystem: FileSystem = FileSystem.SYSTEM
) {
  fun findEmulators(): List<Emulator> {
    return fileSystem.list(registrationDirectory).filter {
      it.name.matches(fileRegex)
    }.map { parseFile(it) }
  }

  private fun parseFile(it: Path): Emulator {
    return fileSystem.read(it) {
      readUtf8().lines().mapNotNull {
        val parts = it.split("=", limit = 2)
        if (parts.size == 2) {
          parts[0] to parts[1]
        } else {
          null
        }
      }
    }.toMap().toEmulator()
  }

  companion object {
    val fileRegex = "pid_\\d+.ini".toRegex()

    private val os = System.getProperty("os.name").lowercase()

    // https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:utp/android-test-plugin-host-retention/src/main/java/com/android/tools/utp/plugins/host/icebox/IceboxConfigUtils.kt?q=8554%20f:android
    fun findRegistrationDirectory() = when {
      os.startsWith("mac") -> macPath()
      os.startsWith("win") -> windowsPath()
      else -> linuxPath()
    } / "avd" / "running"

    private fun linuxPath(): Path {
      val possible = listOfNotNull(
        System.getenv("XDG_RUNTIME_DIR"),
        uid()?.let { "/run/user/$it" },
        System.getenv("ANDROID_EMULATOR_HOME"),
        System.getenv("ANDROID_PREFS_ROOT"),
        System.getenv("ANDROID_SDK_HOME"),
        (System.getenv("HOME") ?: "/") + ".android",
        System.getProperty("android.emulator.home")
      ).map { it.toPath() }

      possible.forEach {
        if (FileSystem.SYSTEM.metadataOrNull(it)?.isDirectory == true) {
          return it
        }
      }

      return (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("android-" + System.getProperty("USER")))
    }

    private fun uid() = try {
      UnixSystem().uid
    } catch (t: Throwable) {
      null
    }

    private fun windowsPath() = (System.getenv("LOCALAPPDATA") ?: "/").toPath() / "Temp"

    private fun macPath() = (System.getenv("HOME") ?: "/").toPath() / "Library" / "Caches" / "TemporaryItems"
  }
}