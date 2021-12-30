package dadb.devices

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceFinderTest {
    val fileSystem = FakeFileSystem()
    val registrationDirectory = "/avd".toPath()
    val finder = DeviceFinder(registrationDirectory, fileSystem)

    @Test
    fun testReadFiles() {
        fileSystem.createDirectories(registrationDirectory)
        fileSystem.write(registrationDirectory / "pid_44126.ini") {
            writeUtf8(
                """
                port.serial=5554
                port.adb=5555
                avd.name=Pixel
                avd.dir=/Users/xxx/.android/avd/Pixel.avd
                avd.id=Pixel
                cmdline="/Users/xxx/Library/Android/sdk/emulator/qemu/darwin-x86_64/qemu-system-x86_64" "-avd" "Pixel"
                grpc.port=8554
                """.trimIndent()
            )
        }

        val devices = finder.findEmulators()

        assertEquals(
            Emulator(
                id = "Pixel",
                deviceClass = null,
                serialPort = 5554,
                adbPort = 5555,
                avdDir = "/Users/xxx/.android/avd/Pixel.avd".toPath(),
                grpcPort = 8554,
                cmdline = "\"/Users/xxx/Library/Android/sdk/emulator/qemu/darwin-x86_64/qemu-system-x86_64\" \"-avd\" \"Pixel\"",
            ),
            devices.single()
        )
    }
}