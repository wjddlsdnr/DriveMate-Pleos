package com.example.pleosconnect.data.media

import android.content.Context
import android.net.Uri

/** No generated sample or debug provider in release builds. */
object DebugAudioSample {
    const val available = false
    fun uri(context: Context): Uri = error("Debug sample unavailable")
}
