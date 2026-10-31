package pl.ambienthome.tv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

data class ShellResult(val output: String, val code: Int)
fun interface Shell { fun run(command: String): ShellResult }

/** Minimal legacy TCP ADB transport. Only explicitly requested, bounded shell sessions. */
class AdbWire(private val keys: KeyPair) {
    private data class Packet(val command: Int, val a: Int, val b: Int, val data: ByteArray)
    private fun packet(input: InputStream): Packet {
        fun read(count: Int): ByteArray {
            val bytes = ByteArray(count); var at = 0
            while (at < count) { val n = input.read(bytes, at, count - at); check(n > 0) { "ADB zamknęło połączenie" }; at += n }
            return bytes
        }
        val header = ByteBuffer.wrap(read(24)).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int; val a = header.int; val b = header.int
        val size = header.int; val checksum = header.int; val magic = header.int
        check(magic == command.inv() && size in 0..1_048_576) { "Nieprawidłowa ramka ADB" }
        val body = read(size)
        check(checksum == body.sumOf { it.toInt() and 255 }) { "Nieprawidłowa suma ADB" }
        return Packet(command, a, b, body)
    }
    private fun send(output: OutputStream, cmd: Int, a: Int, b: Int, bytes: ByteArray = byteArrayOf()) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(a).putInt(b).putInt(bytes.size).putInt(bytes.sumOf { it.toInt() and 255 }).putInt(cmd.inv()).array()
        output.write(header); output.write(bytes); output.flush()
    }
    internal fun publicAdbKey(): ByteArray {
        val key = keys.public as RSAPublicKey
        fun little(value: BigInteger): ByteArray {
            val big = value.toByteArray().reversedArray()
            return ByteArray(256) { if (it < big.size) big[it] else 0 }
        }
        val modulus = key.modulus
        val word = BigInteger.ONE.shiftLeft(32)
        val inv = modulus.mod(word).modInverse(word).negate().mod(word).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        val encoded = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).putInt(64).putInt(inv)
            .put(little(modulus)).put(little(rr)).putInt(key.publicExponent.toInt()).array()
        return (Base64.getEncoder().encodeToString(encoded) + " ambient-home@android\u0000").toByteArray(Charsets.US_ASCII)
    }
    private fun signature(token: ByteArray): ByteArray {
        check(token.size == 20) { "Nieprawidłowy token ADB" }
        val prefix = byteArrayOf(0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.ENCRYPT_MODE, keys.private); doFinal(prefix + token) }
    }
    fun execute(host: String, port: Int, command: String): ShellResult {
        require(port in 1..65535)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2500); socket.soTimeout = 15_000
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            // Version 1.0 retains payload checksums, including with newer adbd versions.
            send(output, CNXN, 0x01000000, 4096, "host::\u0000".toByteArray())
            var reply = packet(input)
            if (reply.command == AUTH && reply.a == 1) {
                send(output, AUTH, 2, 0, signature(reply.data)); reply = packet(input)
                if (reply.command == AUTH && reply.a == 1) {
                    send(output, AUTH, 3, 0, publicAdbKey())
                    socket.soTimeout = 20_000
                    try { reply = packet(input) } catch (e: Exception) { throw IllegalStateException("Zaakceptuj klucz RSA Ambient na ekranie TV, a potem ponów połączenie", e) }
                }
            }
            check(reply.command == CNXN) { "ADB wymaga autoryzacji RSA. Parowanie TLS nie jest obsługiwane." }
            socket.soTimeout = 15_000
            val marker = "__AMBIENT_EXIT_38C7__"
            val request = "shell:$command; printf '\\n$marker%d\\n' \$?\u0000".toByteArray(Charsets.UTF_8)
            send(output, OPEN, 1, 0, request)
            val collected = ByteArrayOutputStream()
            var remote = 0
            while (true) {
                val p = packet(input)
                when (p.command) {
                    OKAY -> { check(p.b == 1); remote = p.a }
                    WRTE -> {
                        check(p.b == 1 && (remote == 0 || remote == p.a)); remote = p.a
                        check(collected.size() + p.data.size <= 2_000_000) { "Wynik ADB jest zbyt duży" }
                        collected.write(p.data); send(output, OKAY, 1, remote)
                    }
                    CLSE -> { check(p.b == 1); send(output, CLSE, 1, p.a); break }
                    else -> error("Nieoczekiwana odpowiedź ADB")
                }
            }
            val text = collected.toString("UTF-8")
            val end = Regex("(?:\\r?\\n)$marker(\\d+)\\r?\\n?$").find(text) ?: error("Brak potwierdzenia zakończenia polecenia ADB")
            return ShellResult(text.substring(0, end.range.first).trimEnd(), end.groupValues[1].toInt())
        }
    }
    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val WRTE = 0x45545257
        private const val CLSE = 0x45534c43
    }
}
