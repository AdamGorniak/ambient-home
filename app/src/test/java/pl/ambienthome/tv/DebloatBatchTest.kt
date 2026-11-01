package pl.ambienthome.tv

import org.junit.Assert.*
import org.junit.Test

class DebloatBatchTest {
    @Test fun batchStopsAfterFirstFailureWithoutAttemptingRemainingPackages() {
        val attempted = mutableListOf<String>()
        val result = DebloatBatch.run(listOf("com.vendor.one", "com.vendor.two", "com.vendor.three")) { pkg ->
            attempted.add(pkg); if (pkg.endsWith("two")) error("connection lost"); "ok"
        }
        assertEquals(listOf("com.vendor.one", "com.vendor.two"), attempted)
        assertEquals(2, result.size); assertTrue(result.last().contains("Przerwano"))
    }
    @Test fun emptyDuplicateOrOversizedPlansCannotExecute() {
        for (plan in listOf(emptyList(), listOf("com.a.one", "com.a.one"), (0..10).map { "com.a.p$it" })) {
            var called = false
            try { DebloatBatch.run(plan) { called = true; "ok" }; fail("Invalid plan accepted") } catch (_: IllegalArgumentException) {}
            assertFalse(called)
        }
    }
    @Test fun automaticProposalsAreOnlyKnownNonCriticalProfiles() {
        val device = DebloatDevice("Xiaomi", "MiTV-MOOR2", "Xiaomi/flipped/venom:11/RTM5.220609.003/2674:user/release-keys")
        val targets = AutomaticDebloat.targets(device)
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.size <= 10)
        for (target in targets) {
            val pkg = target.pkg
            assertTrue(pkg, pkg in DebloatPolicy.profiles)
            assertNull(pkg, DebloatPolicy.protection(pkg, "pl.ambienthome.tv", emptySet()))
        }
    }
}
