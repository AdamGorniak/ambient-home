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
        assertTrue(DebloatPolicy.quickCandidates.isNotEmpty())
        assertTrue(DebloatPolicy.quickCandidates.size <= 10)
        for (pkg in DebloatPolicy.quickCandidates) {
            assertTrue(pkg, pkg in DebloatPolicy.profiles)
            assertNull(pkg, DebloatPolicy.protection(pkg, "pl.ambienthome.tv", emptySet()))
        }
    }
}
