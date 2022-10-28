package dadb

import org.junit.jupiter.api.Test
import java.io.File

class Tmp {

    @Test
    fun tmp() {
        Dadb.create("leland-emulator", 5555).use { dadb ->
            println("Connected")

            dadb.install(File("/Users/leland/samples/sample.apk"))

            println("Installed")
        }
    }
}