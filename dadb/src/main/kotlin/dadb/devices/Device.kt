package dadb.devices

sealed interface Device {
  val id: String
  val deviceClass: DeviceClass?
  val serialPort: Int
  val adbPort: Int

  enum class DeviceClass {
    Mobile, Wear, TV, Auto
  }
}