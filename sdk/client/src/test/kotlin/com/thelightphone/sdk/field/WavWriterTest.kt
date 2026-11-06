package com.thelightphone.sdk.field

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class WavWriterTest {
    @Test fun writesPlayableHeaderAndSamples() {
        val f = File.createTempFile("t", ".wav")
        WavWriter(f, 48_000).use { w ->
            w.write(shortArrayOf(1, -2, 3))
            w.flushHeader()
            w.write(ShortArray(100_000) { (it % 7).toShort() }, 99_999)
            assertEquals(100_002L, w.frames)
        }
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 100_002 * 2, b.capacity())
        assertEquals("RIFF", String(f.readBytes(), 0, 4))
        assertEquals(36 + 100_002 * 2, b.getInt(4))
        assertEquals(48_000, b.getInt(24))
        assertEquals(100_002 * 2, b.getInt(40))
        assertEquals(-2, b.getShort(46).toInt())
        assertEquals(6, b.getShort(44 + 6 + 6 * 2).toInt())
    }
}
