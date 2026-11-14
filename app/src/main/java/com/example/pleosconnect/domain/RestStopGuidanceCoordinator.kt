package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class RestGuidancePhase { IDLE, SEARCHING, ASKING, APPLYING, ADDED, EMPTY, DECLINED, NO_RESPONSE, FAILED, STALE, CANCELLED, UNCERTAIN }
data class RestGuidanceStatus(
    val phase: RestGuidancePhase = RestGuidancePhase.IDLE,
    val failure: FailureReason? = null,
    val candidate: RestStopCandidate? = null,
)

/** A-owned request orchestration only. B owns actual POI filtering and navigation mutation. */
class RestStopGuidanceCoordinator(
    private val safety: StateFlow<SafetyResponse>,
    private val finder: RestStopFinder,
    private val navigation: NavigationController,
    private val voice: VoiceService,
    private val clock: MonotonicClock,
) {
    private data class RouteKey(val id: String, val revision: Long)
    private data class AttemptKey(val route: RouteKey, val driver: DriverStatus)
    private val lock = Mutex()
    private var active: Deferred<Unit>? = null
    private var closed = false
    private var lastClock = -1L
    private val attempted = mutableSetOf<AttemptKey>()
    private var navigationIssued = false
    private val mutableStatus = MutableStateFlow(RestGuidanceStatus())
    val status = mutableStatus.asStateFlow()

    suspend fun request(retry: Boolean = false): Unit = coroutineScope {
        val invalidated = AtomicBoolean(false)
        val task = lock.withLock {
            if (closed || active?.isCompleted == false) return@coroutineScope
            val driver = safety.value.driver
            if (!needsRest(driver)) return@coroutineScope
            val route = navigation.route.value ?: return@coroutineScope
            if (!usable(route)) return@coroutineScope
            val key = AttemptKey(RouteKey(route.routeId, route.revision), driver)
            if (navigationIssued || (!retry && key in attempted)) return@coroutineScope
            attempted += key
            val issuedAt = now()
            val search = RestStopSearch(UUID.randomUUID().toString(), key.route.id, key.route.revision)
            mutableStatus.value = RestGuidanceStatus(RestGuidancePhase.SEARCHING)
            async(start = CoroutineStart.LAZY) {
                guardedRun(key, search, issuedAt, invalidated)
            }.also { active = it; it.start() }
        }
        try { task.await() }
        catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!invalidated.get()) throw cancelled
        }
    }

    private suspend fun guardedRun(key: AttemptKey, search: RestStopSearch, issuedAt: Long, invalidated: AtomicBoolean) = coroutineScope {
        val owner = currentCoroutineContext().job
        var applying = false
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(navigation.route, safety) { _, _ -> fresh(key) }.first { !it }
            invalidated.set(true)
            owner.cancel(CancellationException("Rest guidance invalidated"))
        }
        try {
            currentCoroutineContext().ensureActive()
            checkFresh(key, issuedAt)
            val found = call(15.seconds) { finder.find(search) }
            checkFresh(key, issuedAt)
            val results = when (found) {
                is OperationResult.Success -> found.value.toList()
                is OperationResult.Failure -> { failed(found.reason); unavailable(); return@coroutineScope }
            }
            checkFresh(key, issuedAt)
            if (results.isEmpty()) {
                mutableStatus.value = RestGuidanceStatus(RestGuidancePhase.EMPTY)
                unavailable(); return@coroutineScope
            }
            if (results.any { it.id.isBlank() || it.name.isBlank() } || results.map { it.id }.distinct().size != results.size) {
                failed(FailureReason.INVALID_REQUEST); unavailable(); return@coroutineScope
            }
            val candidate = results.first()
            mutableStatus.value = RestGuidanceStatus(RestGuidancePhase.ASKING, candidate = candidate)
            val spoken = call(30.seconds) {
                voice.speak("${candidate.name}을 휴식 경유지로 추가할까요? 기존 목적지는 유지합니다.")
            }
            if (spoken is OperationResult.Failure) { failed(spoken.reason); return@coroutineScope }
            checkFresh(key, issuedAt)
            val answer = try { withTimeoutOrNull(10.seconds) { voice.listen(10.seconds) } ?: RecognitionResult.Timeout }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { RecognitionResult.PermissionDenied }
            catch (_: Exception) { RecognitionResult.ServiceUnavailable }
            checkFresh(key, issuedAt)
            val affirmative = answer is RecognitionResult.Text && answer.value.replace(Regex("\\s+"), "")
                .trimEnd('.', '!') in setOf("응", "네", "예", "동의해", "동의합니다", "좋아", "추가해줘", "안내해줘")
            if (!affirmative) {
                when (answer) {
                    RecognitionResult.NoResponse -> mutableStatus.value = status.value.copy(phase = RestGuidancePhase.NO_RESPONSE)
                    RecognitionResult.Timeout -> failed(FailureReason.TIMEOUT)
                    RecognitionResult.PermissionDenied -> failed(FailureReason.PERMISSION_DENIED)
                    RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable -> failed(FailureReason.SERVICE_UNAVAILABLE)
                    else -> mutableStatus.value = status.value.copy(phase = RestGuidancePhase.DECLINED)
                }
                return@coroutineScope
            }
            currentCoroutineContext().ensureActive()
            checkFresh(key, issuedAt)
            val consent = DriverConsent(ConsentScope.REST_WAYPOINT, candidate.id, now())
            // Own successful waypoint insertion may legitimately publish a new route revision.
            watcher.cancelAndJoin()
            checkFresh(key, issuedAt)
            navigationIssued = true
            applying = true
            mutableStatus.value = status.value.copy(phase = RestGuidancePhase.APPLYING)
            when (val added = call(15.seconds) { navigation.addRestWaypoint(search, candidate, consent) }) {
                is OperationResult.Success -> mutableStatus.value = status.value.copy(phase = RestGuidancePhase.ADDED)
                is OperationResult.Failure -> mutableStatus.value = status.value.copy(phase = RestGuidancePhase.UNCERTAIN, failure = added.reason)
            }
        } catch (cancelled: CancellationException) {
            mutableStatus.value = status.value.copy(failure = if (invalidated.get()) FailureReason.STALE_REQUEST else status.value.failure, phase = when {
                applying -> RestGuidancePhase.UNCERTAIN
                invalidated.get() -> RestGuidancePhase.STALE
                else -> RestGuidancePhase.CANCELLED
            })
            throw cancelled
        } catch (_: StaleResult) {
            invalidated.set(true)
            mutableStatus.value = status.value.copy(phase = RestGuidancePhase.STALE, failure = FailureReason.STALE_REQUEST)
        } finally {
            watcher.cancel()
            withContext(NonCancellable) { call(5.seconds) { voice.stop(); OperationResult.Success(Unit) } }
        }
    }

    suspend fun cancel() = lock.withLock { active?.cancelAndJoin(); active = null }
    suspend fun close() = lock.withLock {
        closed = true
        active?.cancelAndJoin()
        active = null
    }

    private fun needsRest(driver: DriverStatus) = safety.value.restStopNeeded && driver.state in
        setOf(DriverState.WARNING, DriverState.HIGH_RISK, DriverState.RECOVERY_CHECK)
    private fun fresh(key: AttemptKey): Boolean {
        val current = navigation.route.value ?: return false
        return needsRest(safety.value.driver) && safety.value.driver == key.driver &&
            current.routeId == key.route.id && current.revision == key.route.revision && usable(current)
    }
    private fun checkFresh(key: AttemptKey, issuedAt: Long) {
        if (!fresh(key) || now() - issuedAt > 60_000) throw StaleResult()
    }
    private fun usable(route: RouteProgress): Boolean {
        val now = now()
        val remaining = route.remaining.inWholeMilliseconds
        return route.routeId.isNotBlank() && route.revision >= 0 && route.observedAtMillis in 0..now &&
            route.remaining.isFinite() && remaining > 0 && remaining <= Long.MAX_VALUE - route.observedAtMillis &&
            route.observedAtMillis + remaining > now
    }
    private suspend fun unavailable() {
        call(30.seconds) { voice.speak("휴식 장소 안내를 완료하지 못했습니다. 안전한 곳에 정차해 휴식하세요.") }
    }
    private fun failed(reason: FailureReason) { mutableStatus.value = status.value.copy(phase = RestGuidancePhase.FAILED, failure = reason) }
    private suspend fun <T> call(timeout: Duration, block: suspend () -> OperationResult<T>): OperationResult<T> = try {
        withTimeoutOrNull(timeout) { block() } ?: OperationResult.Failure(FailureReason.TIMEOUT)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: SecurityException) { OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
    catch (_: Exception) { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
    @Synchronized private fun now(): Long {
        val now = clock.nowMillis()
        require(now >= 0 && now >= lastClock)
        lastClock = now
        return now
    }
    private class StaleResult : RuntimeException()
}
