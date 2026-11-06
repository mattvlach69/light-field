package com.thelightphone.sdk.field

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes mono 16-bit PCM to a .wav file. The header's sizes are refreshed by [flushHeader],
 * so a recording cut off by a crash or a dead battery is still a playable file.
 */
class WavWriter(file: File, private val sampleRate: Int) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L
    private val scratch = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

    val frames: Long get() = dataBytes / 2

    init {
        writeHeader()
    }

    fun write(samples: ShortArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            scratch.clear()
            val n = minOf(count - i, scratch.capacity() / 2)
            for (k in 0 until n) scratch.putShort(samples[i + k])
            out.write(scratch.array(), 0, n * 2)
            i += n
        }
        dataBytes += count * 2L
    }

    fun flushHeader() {
        val pos = out.filePointer
        writeHeader()
        out.seek(pos)
    }

    override fun close() {
        flushHeader()
        out.close()
    }

    private fun writeHeader() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        h.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        out.seek(0)
        out.write(h.array())
    }
}
