package `in`.dragonbra.javasteam.util

import com.github.luben.zstd.Zstd
import `in`.dragonbra.javasteam.util.log.LogManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

object VZstdUtil {

    private const val VZSTD_HEADER: Int = 0x615A5356

    private val logger = LogManager.getLogger<VZstdUtil>()

    @Throws(IOException::class, IllegalArgumentException::class)
    @JvmStatic
    @JvmOverloads
    fun decompress(buffer: ByteArray, destination: ByteArray, verifyChecksum: Boolean = false): Int =
        decompress(buffer, buffer.size, destination, verifyChecksum)

    @Throws(IOException::class, IllegalArgumentException::class)
    @JvmStatic
    @JvmOverloads
    fun decompress(buffer: ByteArray, length: Int, destination: ByteArray, verifyChecksum: Boolean = false): Int {
        require(length >= 23 && length <= buffer.size) { "Invalid VZstd stream length" }
        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        val header = byteBuffer.getInt(0)
        if (header != VZSTD_HEADER) {
            throw IOException("Expecting VZstdHeader at start of stream")
        }

        val crc32 = byteBuffer.getInt(4)
        val crc32Footer = byteBuffer.getInt(length - 15)
        val sizeDecompressed = byteBuffer.getInt(length - 11)

        if (crc32 == crc32Footer) {
            // They write CRC32 twice?
            // logger.debug("CRC32 appears to be written twice in the data")
        }

        if (buffer[length - 3] != 'z'.code.toByte() ||
            buffer[length - 2] != 's'.code.toByte() ||
            buffer[length - 1] != 'v'.code.toByte()
        ) {
            throw IOException("Expecting VZstdFooter at end of stream")
        }

        if (destination.size < sizeDecompressed) {
            throw IllegalArgumentException("The destination buffer is smaller than the decompressed data size.")
        }

        try {
            val bytesDecompressed = Zstd.decompressByteArray(destination, 0, destination.size, buffer, 8, length - 23)

            if (bytesDecompressed != sizeDecompressed.toLong()) {
                throw IOException("Failed to decompress Zstd (expected $sizeDecompressed bytes, got $bytesDecompressed).")
            }

            if (verifyChecksum) {
                val crc = CRC32()
                crc.update(destination, 0, sizeDecompressed)
                val calculatedCrc = crc.value.toInt()
                if (calculatedCrc != crc32Footer) {
                    throw IOException("CRC does not match decompressed data. VZstd data may be corrupted.")
                }
            }

            return sizeDecompressed
        } catch (e: NoClassDefFoundError) {
            // Zstd is a 'compileOnly' dependency. If it's missing, throw the correct type of error.
            logger.error("Missing implementation of com.github.luben:zstd-jni")
            throw e
        } catch (e: Exception) {
            // Catch all for the Zstd library.
            throw IOException("Failed to decompress Zstd data: ${e.message}", e)
        }
    }
}
