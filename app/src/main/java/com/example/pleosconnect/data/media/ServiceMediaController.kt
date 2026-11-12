package com.example.pleosconnect.data.media

import com.example.pleosconnect.contract.integration.MediaCommand
import com.example.pleosconnect.contract.integration.MediaController
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A-compatible port. Not wired to scripted voice; UI must explicitly choose a real service. */
class ServiceMediaController(private val gateway: MediaServiceGateway) : MediaController {
    private val lock = Mutex()
    private var ready = false
    private var generation = 0L
    suspend fun connect(app: MediaApp): MediaConnection = lock.withLock {
        disconnect()
        val epoch = generation
        try {
            val result = withTimeout(8_000) { gateway.connect(app) }
            check(epoch == generation)
            ready = true
            result
        } catch (error: Throwable) { disconnect(); throw error }
    }
    fun disconnect() { generation++; ready = false; gateway.disconnect() }
    override suspend fun execute(command: MediaCommand): OperationResult<Unit> = lock.withLock {
        if (!ready) return@withLock OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        // No news provider is configured. Never reinterpret a generic music search as news.
        if (command == MediaCommand.PlayNewsAudio) return@withLock OperationResult.Failure(FailureReason.UNSUPPORTED)
        if (command is MediaCommand.PlayMusic && (command.query.isBlank() || command.query.length > 200))
            return@withLock OperationResult.Failure(FailureReason.INVALID_REQUEST)
        checked { gateway.command(command) }
    }
    suspend fun play(entry: MediaEntry): OperationResult<Unit> = lock.withLock {
        if (!ready) return@withLock OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        if (!entry.playable || entry.id.isBlank()) return@withLock OperationResult.Failure(FailureReason.INVALID_REQUEST)
        checked { gateway.play(entry) }
    }
    private suspend fun checked(block: suspend () -> OperationResult<Unit>): OperationResult<Unit> {
        val epoch = generation
        val result = try { withTimeout(8_000) { block() } }
        catch (_: TimeoutCancellationException) { OperationResult.Failure(FailureReason.TIMEOUT) }
        catch (error: CancellationException) { throw error }
        catch (_: SecurityException) { OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
        return if (generation == epoch) result else OperationResult.Failure(FailureReason.STALE_REQUEST)
    }
}
