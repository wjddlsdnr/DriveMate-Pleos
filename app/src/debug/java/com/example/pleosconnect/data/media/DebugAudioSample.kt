package com.example.pleosconnect.data.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Generated test tone, not music or a news service. Debug builds only. No private file access. */
object DebugAudioSample {
    const val available = true
    fun uri(context: Context): Uri {
        val folder = File(context.cacheDir, "b5-generated-audio").apply { check(isDirectory || mkdirs()) }
        val file = File(folder, "drivemate-sample.wav")
        if (!file.exists()) {
            val rate = 16_000
            val samples = rate * 8
            val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples * 2)
            repeat(samples) { i ->
                val fade = minOf(1.0, i / 800.0, (samples - i) / 800.0)
                buffer.putShort((sin(2 * PI * 440 * i / rate) * 1_200 * fade).toInt().toShort())
            }
            file.writeBytes(buffer.array())
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.debug.audio", file)
    }
}
