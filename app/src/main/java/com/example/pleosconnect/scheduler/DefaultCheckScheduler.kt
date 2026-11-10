package com.example.pleosconnect.scheduler

import com.example.pleosconnect.contract.integration.RouteProgress
import com.example.pleosconnect.contract.logic.CheckKind
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.logic.ScheduledCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** In-memory scheduling only. No timer, SDK calls or speech; caller polls takeDue(). */
class DefaultCheckScheduler(private val clock: MonotonicClock) : FollowUpScheduler {
    private val mutablePending = MutableStateFlow<List<ScheduledCheck>>(emptyList())
    override val pending = mutablePending.asStateFlow()
    private var route: RouteProgress? = null
    private var arrival = 0L
    private val retiredRoutes = mutableSetOf<String>()
    private val checks = mutableListOf<ScheduledCheck>()
    private var departureConsumed = false
    private var lastPeriodic: Long? = null
    private var lastDelivered: Long? = null
    private var lastClock = -1L
    private var sequence = 0L
    private var pausedForRest = false
    private var pausedAt = 0L
    private var pausedRemaining = 0L

    @Synchronized override fun onRouteUpdated(route: RouteProgress?) {
        val now = now()
        if (route == null) { clearTrip(); publish(); return }
        require(route.routeId.isNotBlank() && route.revision >= 0)
        require(route.observedAtMillis in 0..now)
        require(route.remaining.isFinite() && route.remaining >= Duration.ZERO)
        val millis = route.remaining.inWholeMilliseconds
        require(route.remaining == Duration.ZERO || millis > 0)
        require(route.remaining <= (Long.MAX_VALUE - route.observedAtMillis).milliseconds)
        val end = Math.addExact(route.observedAtMillis, millis)
        val intervalBase = if (millis <= 60 * MINUTE) 20 * MINUTE else 30 * MINUTE
        val segments = if (millis == 0L) 1L else (millis - 1) / intervalBase + 1
        require(segments <= 10_000)
        if (route.routeId in retiredRoutes) return
        val old = this.route
        if (old != null && old.routeId == route.routeId &&
            (route.revision <= old.revision || route.observedAtMillis < old.observedAtMillis)) return
        if (old != null && old.routeId != route.routeId && route.observedAtMillis < old.observedAtMillis) return
        if (pausedForRest) {
            if (old != null && old.routeId != route.routeId) retiredRoutes += old.routeId
            this.route = route
            arrival = end
            pausedRemaining = (end - maxOf(pausedAt, route.observedAtMillis)).coerceAtLeast(0)
            if (millis == 0L) clearTrip()
            publish()
            return
        }
        if (old?.routeId != route.routeId) {
            clearTrip()
            departureConsumed = false
            lastPeriodic = null
        }
        val oldNext = checks.filter { it.kind == CheckKind.PERIODIC }.minByOrNull { it.dueAtMillis }
        this.route = route
        arrival = end
        if (millis == 0L) { clearTrip(); publish(); return }
        checks.removeAll { it.kind == CheckKind.PERIODIC || it.dueAtMillis >= arrival }
        if (end <= now) { checks.clear(); publish(); return }
        val floor = lastPeriodic?.let { saturatedAdd(it, 10 * MINUTE) } ?: 0L
        val times = mutableSetOf<Long>()
        if (!departureConsumed) times += maxOf(now, floor)
        for (index in 1 until segments.toInt()) {
            // Avoid millis * index overflow while preserving integer division.
            val offset = (millis / segments) * index + ((millis % segments) * index) / segments
            val due = route.observedAtMillis + offset
            if (due >= floor && due > now) times += due
        }
        if (oldNext != null && oldNext.dueAtMillis >= floor && oldNext.dueAtMillis < arrival) {
            val first = times.minOrNull()
            if (first == null || oldNext.dueAtMillis < first) times += oldNext.dueAtMillis
        }
        times.filter { it < arrival }.sorted().forEach { checks += check(CheckKind.PERIODIC, it) }
        publish()
    }

    @Synchronized override fun scheduleFollowUp(kind: CheckKind, delay: Duration) {
        require(kind != CheckKind.PERIODIC)
        require(delay.isFinite() && delay > Duration.ZERO)
        val now = now()
        if (pausedForRest) return
        require(delay <= (Long.MAX_VALUE - now).milliseconds)
        val whole = delay.inWholeMilliseconds
        val rounded = whole + if (delay > whole.milliseconds) 1 else 0
        val due = Math.addExact(now, rounded)
        if (route == null || now >= arrival) { checks.clear(); publish(); return }
        if (due >= arrival) return
        val existing = checks.singleOrNull { it.kind == kind }
        if (existing == null || due < existing.dueAtMillis) {
            checks.removeAll { it.kind == kind }
            checks += check(kind, due)
        }
        publish()
    }

    @Synchronized override fun takeDue(): List<ScheduledCheck> {
        val now = now()
        if (pausedForRest) return emptyList()
        if (route == null || now >= arrival) { checks.clear(); publish(); return emptyList() }
        if (lastDelivered?.let { now - it < MINUTE } == true) return emptyList()
        val due = checks.filter { it.dueAtMillis <= now }
        val selected = due.minWithOrNull(compareBy<ScheduledCheck> { priority(it.kind) }.thenBy { it.dueAtMillis })
            ?: return emptyList()
        if (due.any { it.kind == CheckKind.PERIODIC }) {
            departureConsumed = true
            lastPeriodic = now
        }
        checks.removeAll { it.dueAtMillis <= now }
        if (due.any { it.kind == CheckKind.PERIODIC }) {
            checks.removeAll { it.kind == CheckKind.PERIODIC && it.dueAtMillis < saturatedAdd(now, 10 * MINUTE) }
        }
        lastDelivered = now
        publish()
        return listOf(selected)
    }

    @Synchronized override fun cancelAll() { now(); clearTrip(); publish() }

    /** Suspend questions without retiring the active route. Repeated calls do not move the anchor. */
    @Synchronized fun pauseForRest() {
        val now = now()
        if (pausedForRest) return
        pausedForRest = true
        pausedAt = now
        pausedRemaining = if (route == null) 0 else (arrival - now).coerceAtLeast(0)
        checks.clear()
        publish()
    }

    /** Call only after recovery and explicit route-resume consent succeeded. */
    @Synchronized fun resumeAfterRest(latest: RouteProgress?) {
        if (!pausedForRest) return
        onRouteUpdated(latest) // Validate updates while paused; null/arrival remain terminal.
        if (!pausedForRest) return
        val saved = route ?: return
        val remaining = pausedRemaining
        val now = now()
        pausedForRest = false
        route = null // Rebuild without marking the paused route as retired.
        onRouteUpdated(saved.copy(remaining = remaining.milliseconds, observedAtMillis = now))
        // Recovery already supplied a condition observation. Do not ask again at departure.
        departureConsumed = true
        lastPeriodic = now
        lastDelivered = now
        checks.removeAll { it.dueAtMillis <= now }
        publish()
    }

    @Synchronized override fun cancelFollowUps() {
        now()
        checks.removeAll { it.kind != CheckKind.PERIODIC }
        publish()
    }

    private fun clearTrip() {
        pausedForRest = false
        pausedRemaining = 0
        route?.let { retiredRoutes += it.routeId }
        route = null
        checks.clear()
        departureConsumed = false
        lastPeriodic = null
    }
    private fun check(kind: CheckKind, due: Long): ScheduledCheck {
        sequence = Math.incrementExact(sequence)
        return ScheduledCheck("check-$sequence", kind, due)
    }
    private fun publish() { mutablePending.value = checks.sortedWith(compareBy<ScheduledCheck> { it.dueAtMillis }.thenBy { priority(it.kind) }) }
    private fun now(): Long {
        val value = clock.nowMillis()
        require(value >= 0 && value >= lastClock)
        lastClock = value
        return value
    }
    private fun priority(kind: CheckKind) = when (kind) {
        CheckKind.RISK_FOLLOW_UP -> 0
        CheckKind.RECOVERY -> 1
        CheckKind.PERIODIC -> 2
    }
    private fun saturatedAdd(value: Long, delta: Long) = if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
    private companion object { const val MINUTE = 60_000L }
}
