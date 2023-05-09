package dadb

import org.junit.jupiter.api.Test
import java.net.Socket
import kotlin.test.assertFails
import kotlin.test.assertNotNull

internal class DadbTestImpl : DadbTest() {

    override fun localEmulator(body: (dadb: Dadb) -> Unit) {
        val socket = Socket("localhost", 5555)
        val keyPair = AdbKeyPair.readDefault()
        val connection = AdbConnection.connect(socket, keyPair)
        TestDadb(connection).use(body)
        connection.ensureEmpty()
    }

    private class TestDadb(
        private val connection: AdbConnection,
    ) : Dadb {

        override fun open(destination: String) = connection.open(destination)

        override fun supportsFeature(feature: String) = connection.supportsFeature(feature)

        override fun close() = connection.close()
    }

    @Test
    fun validDefaultConstructorValues() {
        val dadb = Dadb.create("localhost", 5555)
        assertNotNull(dadb, "Unable to create Dadb object with default constructor values")
    }


    @Test
    fun validConstructorValues() {
        val dadb = Dadb.create("localhost", 5555, connectTimeout = 1000, socketTimeout = 10000)
        assertNotNull(dadb, "Unable to create Dadb object with valid constructor values")
    }

    @Test
    fun invalidPortConstructorValue() {
        assertFails("Invalid port value was not validated") {
            Dadb.create("localhost", -1, connectTimeout = 0, socketTimeout = 0)
        }
    }

    @Test
    fun invalidconnectTimeoutConstructorValue() {
        assertFails("Invalid connectTimeout value was not validated") {
            Dadb.create("localhost", 5555, connectTimeout = -1, socketTimeout = 0)
        }
    }

    @Test
    fun invalidSocketTimeoutConstructorValue() {
        assertFails("Invalid socketTimeout value was not validated") {
            Dadb.create("localhost", 5555, connectTimeout = 0, socketTimeout = -1)
        }
    }

}
