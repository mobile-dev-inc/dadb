package dadb.devices

import okio.Path
import okio.Path.Companion.toPath

data class Emulator(
    override val id: String,
    override val deviceClass: Device.DeviceClass?,
    override val serialPort: Int,
    override val adbPort: Int,
    val avdDir: Path,
    val grpcPort: Int?,
    val cmdline: String?
) : Device {

  // port.serial=5554
  // port.adb=5555
  // avd.name=Wear_30_2
  // avd.dir=.android/avd/Wear_30_2.avd
  // avd.id=Wear_30_2
  // cmdline="emulator/qemu/darwin-x86_64/qemu-system-x86_64" "-avd" "Wear_30_2"
  // grpc.port=8554
  companion object {
    fun Map<String, String>.toEmulator(): Emulator {
      return Emulator(
        id = this["avd.id"]!!,
        deviceClass = null,
        serialPort = this["port.serial"]!!.toInt(),
        adbPort = this["port.adb"]!!.toInt(),
        avdDir = this["avd.dir"]!!.toPath(),
        grpcPort = this["grpc.port"]?.toInt(),
        cmdline = this["cmdline"]
      )
    }
  }
}