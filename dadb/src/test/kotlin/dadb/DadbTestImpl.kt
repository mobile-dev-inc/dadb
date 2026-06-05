package dadb

import org.junit.jupiter.api.Test
import java.net.Socket
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
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

    // AdbStreamOpenException is a direct-adbd concept; it lives here (direct connection) rather than
    // in the shared DadbTest, because the adb-server path (AdbServerTest) still surfaces a generic
    // IOException for a refused service (that path is out of scope for the error-model redesign).
    @Test
    fun open_invalidService_throwsStreamOpenException() {
        localEmulator { dadb ->
            assertFailsWith<AdbStreamOpenException> {
                dadb.open("definitely-not-a-real-service:")
            }
        }
    }

}
