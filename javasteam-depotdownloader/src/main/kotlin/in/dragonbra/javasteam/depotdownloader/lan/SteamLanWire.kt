package `in`.dragonbra.javasteam.depotdownloader.lan

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object SteamLanWire {
    private val udpMagic = byteArrayOf(-1, -1, -1, -1, 0x21, 0x4c, 0x5f, 0xa0.toByte())
    private val tcpMagic = "VT01".toByteArray(Charsets.US_ASCII)
    const val discoveryPort = 27036
    const val chunkRequest = 9902
    const val chunkResponse = 9903
    const val maxFrameBytes = 4 * 1024 * 1024

    data class Status(val clientId: Long, val hostname: String, val addresses: List<String>, val steamIds: List<Long>)
    data class Answer(
        val result: Int,
        val appId: Int,
        val depotId: Int,
        val sha: ByteArray,
        val encrypted: Boolean,
        val compressed: Boolean,
        val data: ByteArray,
    )

    private fun little(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun little(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    fun read32(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= data.size - 4)
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun varint(out: ByteArrayOutputStream, original: Long) {
        var value = original
        while (value and -128L != 0L) {
            out.write(((value and 127L) or 128L).toInt())
            value = value ushr 7
        }
        out.write(value.toInt())
    }
    private fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) = varint(out, (field * 8 + wire).toLong())
    private fun number(out: ByteArrayOutputStream, field: Int, value: Long) { tag(out, field, 0); varint(out, value) }
    private fun fixed64(out: ByteArrayOutputStream, field: Int, value: Long) { tag(out, field, 1); out.write(little(value)) }
    private fun bytes(out: ByteArrayOutputStream, field: Int, data: ByteArray) {
        tag(out, field, 2); varint(out, data.size.toLong()); out.write(data)
    }
    private fun string(out: ByteArrayOutputStream, field: Int, value: String) = bytes(out, field, value.toByteArray(Charsets.UTF_8))

    private class Fields(private val data: ByteArray) {
        private var offset = 0
        fun next(): Pair<Int, Int>? {
            if (offset == data.size) return null
            val value = readVarint()
            val field = (value ushr 3).toInt()
            val wire = (value and 7).toInt()
            require(field in 1..536870911 && wire in listOf(0, 1, 2, 5)) { "Invalid protobuf tag" }
            return field to wire
        }
        fun readVarint(): Long {
            var result = 0L
            repeat(10) { i ->
                require(offset < data.size) { "Truncated varint" }
                val value = data[offset++].toInt() and 255
                if (i == 9) require(value <= 1)
                result = result or ((value and 127).toLong() shl (i * 7))
                if (value < 128) return result
            }
            error("Oversized varint")
        }
        fun readBytes(): ByteArray {
            val count = readVarint()
            require(count >= 0 && count <= data.size - offset) { "Bad protobuf length" }
            val end = offset + count.toInt()
            return data.copyOfRange(offset, end).also { offset = end }
        }
        fun readFixed64(): Long {
            require(offset <= data.size - 8)
            return ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long.also { offset += 8 }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> { require(offset <= data.size - 8); offset += 8 }
                2 -> readBytes()
                5 -> { require(offset <= data.size - 4); offset += 4 }
                else -> error("Invalid wire type")
            }
        }
    }

    fun discoveryPacket(clientId: Long, sequence: Int): ByteArray {
        val header = ByteArrayOutputStream().apply { number(this, 1, clientId); number(this, 2, 0) }.toByteArray()
        val body = ByteArrayOutputStream().apply { number(this, 1, sequence.toLong()) }.toByteArray()
        return udpPacket(header, body)
    }

    fun statusPacket(clientId: Long, steamId: Long, hostname: String, localAddress: String): ByteArray {
        val header = ByteArrayOutputStream().apply {
            number(this, 1, clientId)
            number(this, 2, 1)
            number(this, 3, clientId)
        }.toByteArray()
        val user = ByteArrayOutputStream().apply { fixed64(this, 1, steamId); number(this, 2, 1) }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            number(this, 1, 8)
            number(this, 2, 6)
            number(this, 3, discoveryPort.toLong())
            string(this, 4, hostname)
            number(this, 7, -102)
            number(this, 8, 1)
            bytes(this, 9, user)
            number(this, 11, 1)
            number(this, 12, System.currentTimeMillis() / 1000)
            if (localAddress.isNotEmpty()) string(this, 20, localAddress)
            number(this, 23, 2)
            number(this, 31, 1)
        }.toByteArray()
        return udpPacket(header, body)
    }

    private fun udpPacket(header: ByteArray, body: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(udpMagic); write(little(header.size)); write(header); write(little(body.size)); write(body)
        }.toByteArray()

    fun packetType(packet: ByteArray): Int? = parsePacket(packet)?.first

    fun status(packet: ByteArray): Status? {
        val (type, header, body) = parsePacket(packet) ?: return null
        if (type != 1) return null
        var clientId = 0L
        val hdr = Fields(header)
        while (true) {
            val (field, wire) = hdr.next() ?: break
            if (field == 1 && wire == 0) clientId = hdr.readVarint() else hdr.skip(wire)
        }
        if (clientId == 0L) return null
        var hostname = ""
        val addresses = mutableListOf<String>()
        val users = mutableListOf<Long>()
        val fields = Fields(body)
        while (true) {
            val (field, wire) = fields.next() ?: break
            when {
                field == 4 && wire == 2 -> hostname = fields.readBytes().toString(Charsets.UTF_8)
                field == 20 && wire == 2 -> addresses += fields.readBytes().toString(Charsets.UTF_8)
                field == 9 && wire == 2 -> {
                    val sub = Fields(fields.readBytes())
                    while (true) {
                        val (f, w) = sub.next() ?: break
                        if (f == 1 && w == 1) users += sub.readFixed64() else sub.skip(w)
                    }
                }
                else -> fields.skip(wire)
            }
        }
        return Status(clientId, hostname, addresses, users)
    }

    private fun parsePacket(packet: ByteArray): Triple<Int, ByteArray, ByteArray>? {
        if (packet.size < 16 || !packet.copyOfRange(0, 8).contentEquals(udpMagic)) return null
        val headerLength = read32(packet, 8)
        if (headerLength < 0 || headerLength > packet.size - 16) return null
        val bodyLengthOffset = 12 + headerLength
        val bodyLength = read32(packet, bodyLengthOffset)
        if (bodyLength < 0 || bodyLength > packet.size - bodyLengthOffset - 4) return null
        val header = packet.copyOfRange(12, bodyLengthOffset)
        val body = packet.copyOfRange(bodyLengthOffset + 4, bodyLengthOffset + 4 + bodyLength)
        var type = 0
        val fields = Fields(header)
        while (true) {
            val (field, wire) = fields.next() ?: break
            if (field == 2 && wire == 0) type = fields.readVarint().toInt() else fields.skip(wire)
        }
        return Triple(type, header, body)
    }

    fun frameChunkRequest(appId: Int, depotId: Int, sha: ByteArray, token: Long): ByteArray {
        require(sha.size == 20)
        val body = ByteArrayOutputStream().apply {
            number(this, 1, appId.toLong()); number(this, 2, depotId.toLong())
            bytes(this, 3, sha); number(this, 4, token)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(little(body.size + 8)); write(tcpMagic)
            write(little(chunkRequest or Int.MIN_VALUE)); write(little(0)); write(body)
        }.toByteArray()
    }

    fun parseChunkFrame(frame: ByteArray): Answer {
        require(frame.size >= 8 && frame.size <= maxFrameBytes)
        val message = read32(frame, 0) and Int.MAX_VALUE
        require(message == chunkResponse) { "Unexpected Steam peer message $message" }
        val headerLength = read32(frame, 4)
        require(headerLength >= 0 && headerLength <= frame.size - 8)
        val fields = Fields(frame.copyOfRange(8 + headerLength, frame.size))
        var result = -1
        var app = 0
        var depot = 0
        var sha = byteArrayOf()
        var encrypted = false
        var compressed = false
        var data = byteArrayOf()
        while (true) {
            val (field, wire) = fields.next() ?: break
            when {
                field == 1 && wire == 0 -> result = fields.readVarint().toInt()
                field == 2 && wire == 0 -> app = fields.readVarint().toInt()
                field == 3 && wire == 0 -> depot = fields.readVarint().toInt()
                field == 4 && wire == 2 -> sha = fields.readBytes()
                field == 5 && wire == 0 -> encrypted = fields.readVarint() != 0L
                field == 6 && wire == 0 -> compressed = fields.readVarint() != 0L
                field == 7 && wire == 2 -> data = fields.readBytes()
                else -> fields.skip(wire)
            }
        }
        require(data.size <= maxFrameBytes - 256)
        if (result == 1) require(sha.size == 20) { "Steam peer success response lacks chunk SHA" }
        return Answer(result, app, depot, sha, encrypted, compressed, data)
    }
}
