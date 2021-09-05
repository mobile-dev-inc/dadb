package dadb

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher


internal object AuthUtils {

    private const val KEY_LENGTH_BITS = 2048
    private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
    private const val KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4

    private val SIGNATURE_PADDING = ubyteArrayOf(
            0x00u, 0x01u, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu,
            0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0xffu, 0x00u,
            0x30u, 0x21u, 0x30u, 0x09u, 0x06u, 0x05u, 0x2bu, 0x0eu, 0x03u, 0x02u, 0x1au, 0x05u, 0x00u,
            0x04u, 0x14u
    ).toByteArray()

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun signPayload(privateKey: PrivateKey, message: AdbMessage): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(message.payload, 0, message.payloadLength)
    }

    fun readDefaultKeyPair(): KeyPair? {
        val privateKeyFile = File(System.getenv("HOME"), ".android/adbkey")
        val publicKeyFile = File(System.getenv("HOME"), ".android/adbkey.pub")
        if (!privateKeyFile.exists() || !publicKeyFile.exists()) return null

        val publicKey = readAdbPublicKey(privateKeyFile)
        val privateKey = readPKCS1PrivateKey(privateKeyFile)
        return KeyPair(publicKey, privateKey)
    }

    private fun readAdbPublicKey(file: File): PublicKey {
        val keySpec = X509EncodedKeySpec(file.readBytes())
        return KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }

    private fun readPKCS1PrivateKey(file: File): PrivateKey {
        val pemParser = file.bufferedReader().use(::PEMParser)
        val converter = JcaPEMKeyConverter().setProvider("BC")
        val privateKeyInfo = PrivateKeyInfo.getInstance(pemParser.readObject())
        return converter.getPrivateKey(privateKeyInfo)
    }

    private fun convertRsaPublicKeyToAdbFormat(pubkey: RSAPublicKey): ByteArray? {
        val r32 = BigInteger.ZERO.setBit(32)
        var n = pubkey.modulus
        val r = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32)
        var rr = r.modPow(BigInteger.valueOf(2), n)
        var rem = n.remainder(r32)
        val n0inv = rem.modInverse(r32)

        val myN = IntArray(KEY_LENGTH_WORDS)
        val myRr = IntArray(KEY_LENGTH_WORDS)
        var res: Array<BigInteger>
        for (i in 0 until KEY_LENGTH_WORDS) {
            res = rr.divideAndRemainder(r32)
            rr = res[0]
            rem = res[1]
            myRr[i] = rem.intValueExact()
            res = n.divideAndRemainder(r32)
            n = res[0]
            rem = res[1]
            myN[i] = rem.intValueExact()
        }

        val bbuf: ByteBuffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        bbuf.putInt(KEY_LENGTH_WORDS)
        bbuf.putInt(n0inv.negate().intValueExact())
        for (i in myN) bbuf.putInt(i)
        for (i in myRr) bbuf.putInt(i)
        bbuf.putInt(pubkey.publicExponent.intValueExact())
        return bbuf.array()
    }
}
