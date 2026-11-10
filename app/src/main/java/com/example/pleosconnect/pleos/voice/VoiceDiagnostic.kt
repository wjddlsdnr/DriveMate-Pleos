package com.example.pleosconnect.pleos.voice

/** Opt-in metadata only. No transcript, profile, audio buffer or persisted history. */
data class VoiceDiagnostic(val event: String, val textLength: Int? = null)
