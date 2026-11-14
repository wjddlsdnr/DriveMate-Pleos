package com.example.pleosconnect.data.media

import com.example.pleosconnect.contract.integration.MediaCommand
import com.example.pleosconnect.contract.model.OperationResult

data class MediaApp(val id: String, val label: String)
data class MediaEntry(val id: String, val title: String, val browsable: Boolean, val playable: Boolean)
data class MediaConnection(val root: String)

/** Implementations must await observed playback, not just a void transport dispatch. */
interface MediaServiceGateway {
    suspend fun apps(): List<MediaApp>
    suspend fun connect(app: MediaApp): MediaConnection
    suspend fun children(parent: String): List<MediaEntry>
    suspend fun command(command: MediaCommand): OperationResult<Unit>
    suspend fun play(entry: MediaEntry): OperationResult<Unit>
    /** Disconnect does not stop media that may belong to the user or another controller. */
    fun disconnect()
}
