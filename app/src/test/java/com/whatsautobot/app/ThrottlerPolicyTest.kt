package com.whatsautobot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ThrottlerPolicyTest {

    @Test
    fun nextDelayMs_withJitter() {
        // Use a seeded RNG to get deterministic output for test.
        val policy = ThrottlePolicy(
            baseDelayMs = 1000L,
            jitterMs = 500L,
            dailyMax = 100,
            windowStartHour = 9,
            windowEndHour = 21,
            dedupDays = 30,
            rng = Random(42),
        )
        // First call: 1000 + 42.nextLong(0..500) -> 1000 + 42 = 1042 (deterministic)
        val delay = policy.nextDelayMs()
        assertTrue(delay in 1000L..1500L)
    }

    @Test
    fun inWindow_inside() {
        val policy = ThrottlePolicy(0L, 0L, 100, 9, 21, 30)
        assertTrue(policy.inWindow(9))
        assertTrue(policy.inWindow(12))
        assertTrue(policy.inWindow(21))
    }

    @Test
    fun inWindow_outside() {
        val policy = ThrottlePolicy(0L, 0L, 100, 9, 21, 30)
        assertFalse(policy.inWindow(8))
        assertFalse(policy.inWindow(22))
        assertFalse(policy.inWindow(0))
    }

    @Test
    fun overQuota_under() {
        val policy = ThrottlePolicy(0L, 0L, 100, 9, 21, 30)
        assertFalse(policy.overQuota(50))
        assertFalse(policy.overQuota(99))
    }

    @Test
    fun overQuota_atLimit() {
        val policy = ThrottlePolicy(0L, 0L, 100, 9, 21, 30)
        assertTrue(policy.overQuota(100))
        assertTrue(policy.overQuota(150))
    }
}
