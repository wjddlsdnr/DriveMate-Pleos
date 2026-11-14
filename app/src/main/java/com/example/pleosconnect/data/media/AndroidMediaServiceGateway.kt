package com.example.pleosconnect.data.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.example.pleosconnect.contract.integration.MediaCommand
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Public Android API only. No notification access, privileged sessions or vendor calls. Main thread. */
class AndroidMediaServiceGateway(context: Context) : MediaServiceGateway {
    private val context = context.applicationContext
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var generation = 0L
    private data class Observation(val serial: Long = 0, val state: Int? = null, val id: String? = null, val title: String? = null)
    private val observed = MutableStateFlow(Observation())
    private var ownedId: String? = null
    private var pending = false
    override suspend fun apps(): List<MediaApp> = context.packageManager
        .queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
        .filter { it.serviceInfo?.exported == true && it.serviceInfo.enabled }
        .map { MediaApp(ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToString(),
            it.loadLabel(context.packageManager).toString().take(100)) }.distinctBy { it.id }

    override suspend fun connect(app: MediaApp): MediaConnection {
        disconnect()
        require(apps().any { it.id == app.id })
        val epoch = generation
        val complete = CompletableDeferred<MediaConnection>()
        val instance = MediaBrowser(context, requireNotNull(ComponentName.unflattenFromString(app.id)),
            object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    if (epoch != generation) return
                    try {
                    val b = browser ?: return
                    val c = MediaController(context, b.sessionToken)
                    controller = c
                    callback = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) { update(epoch) }
                        override fun onMetadataChanged(metadata: MediaMetadata?) { update(epoch) }
                        override fun onSessionDestroyed() { if (epoch == generation) disconnect() }
                    }.also { c.registerCallback(it, Handler(Looper.getMainLooper())) }
                    update(epoch)
                    complete.complete(MediaConnection(b.root))
                    } catch (error: Exception) {
                        complete.completeExceptionally(error)
                        if (epoch == generation) disconnect()
                    }
                }
                override fun onConnectionFailed() { complete.completeExceptionally(IllegalStateException("Media connection rejected")) }
                override fun onConnectionSuspended() {
                    complete.completeExceptionally(IllegalStateException("Media connection suspended"))
                    if (epoch == generation) disconnect()
                }
            }, null)
        browser = instance
        try { instance.connect(); return withTimeout(7_000) { complete.await() } }
        catch (error: Throwable) { if (epoch == generation) disconnect(); throw error }
    }
    private fun update(epoch: Long) {
        if (epoch != generation) return
        val c = controller ?: return
        val id = c.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (!pending && ownedId != null && id != ownedId) ownedId = null
        observed.value = Observation(observed.value.serial + 1, c.playbackState?.state, id,
            c.metadata?.description?.title?.toString()?.take(150))
    }
    override suspend fun children(parent: String): List<MediaEntry> {
        val b = browser?.takeIf { it.isConnected } ?: error("Disconnected")
        val epoch = generation
        val result = CompletableDeferred<List<MediaEntry>>()
        val subscription = object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                if (epoch == generation) result.complete(children.take(30).mapNotNull {
                    val id = it.mediaId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    MediaEntry(id, it.description.title?.toString()?.take(150) ?: "제목 없음", it.isBrowsable, it.isPlayable)
                })
            }
            override fun onError(parentId: String) { result.completeExceptionally(IllegalStateException("Browse rejected")) }
        }
        try { b.subscribe(parent, subscription); return withTimeout(5_000) { result.await() } }
        finally { b.unsubscribe(parent, subscription) }
    }
    override suspend fun play(entry: MediaEntry): OperationResult<Unit> = dispatch(
        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID, entry.id, false) { it.playFromMediaId(entry.id, null) }

    override suspend fun command(command: MediaCommand): OperationResult<Unit> = when (command) {
        is MediaCommand.PlayMusic -> dispatch(PlaybackState.ACTION_PLAY_FROM_SEARCH, null, false) { it.playFromSearch(command.query, null) }
        MediaCommand.Next -> dispatch(PlaybackState.ACTION_SKIP_TO_NEXT, null, true) { it.skipToNext() }
        MediaCommand.Stop -> dispatch(PlaybackState.ACTION_STOP, null, true, stopping = true) { it.stop() }
        MediaCommand.PlayNewsAudio -> OperationResult.Failure(FailureReason.UNSUPPORTED)
    }
    private suspend fun dispatch(action: Long, target: String?, requireOwned: Boolean, stopping: Boolean = false,
        send: (MediaController.TransportControls) -> Unit): OperationResult<Unit> {
        val c = controller ?: return OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        val before = observed.value
        if (stopping && before.state in listOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_PAUSED, PlaybackState.STATE_NONE))
            return OperationResult.Success(Unit) // Known idle: idempotent Stop must not control unrelated playback.
        if (requireOwned && (ownedId == null || before.id != ownedId))
            return OperationResult.Failure(FailureReason.MANUAL_OVERRIDE)
        if (((c.playbackState?.actions ?: 0) and action) == 0L)
            return OperationResult.Failure(FailureReason.UNSUPPORTED)
        val epoch = generation
        pending = true
        try {
            send(c.transportControls)
            val after = withTimeout(6_000) { observed.first {
                if (generation != epoch) return@first true
                it.serial > before.serial && (it.state == PlaybackState.STATE_ERROR ||
                    if (stopping) it.state in listOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_PAUSED, PlaybackState.STATE_NONE)
                    else it.state == PlaybackState.STATE_PLAYING && !it.id.isNullOrBlank() && !it.title.isNullOrBlank() &&
                        (if (target != null) it.id == target else it.id != before.id || before.state != PlaybackState.STATE_PLAYING))
            } }
            if (generation != epoch) return OperationResult.Failure(FailureReason.STALE_REQUEST)
            if (after.state == PlaybackState.STATE_ERROR) return OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
            ownedId = if (stopping) null else after.id
            return OperationResult.Success(Unit)
        } finally { pending = false }
    }
    override fun disconnect() {
        generation++
        callback?.let { controller?.unregisterCallback(it) }
        browser?.disconnect()
        callback = null; controller = null; browser = null; ownedId = null; pending = false
        observed.value = Observation(observed.value.serial + 1)
    }
}
