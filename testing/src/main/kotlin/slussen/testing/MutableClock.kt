package slussen.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A `java.time.Clock` that tests set and advance; the core never reads the system time itself (ADR-001 §3). */
class MutableClock(
    private var now: Instant = Instant.parse("2026-10-15T12:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    fun set(instant: Instant) {
        now = instant
    }

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
}
