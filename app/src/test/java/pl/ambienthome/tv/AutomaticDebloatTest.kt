package pl.ambienthome.tv

import org.junit.Assert.*
import org.junit.Test

class AutomaticDebloatTest {
    private val device = DebloatDevice("Xiaomi", "MiTV-MOOR2", "Xiaomi/flipped/venom:11/RTM5.220609.003/2674:user/release-keys")
    private val pkg = "com.miui.tv.analytics"
    private class Journal : DebloatJournal {
        val rows = mutableListOf<DebloatChange>()
        override fun entries() = rows.toList()
        override fun put(change: DebloatChange) { rows.removeAll { it.pkg == change.pkg }; rows.add(change) }
    }
    private class Fake : Shell {
        var version = "2023080917"
        var uid = 10047
        var system = true
        var state = 0
        val commands = mutableListOf<String>()
        override fun run(command: String): ShellResult {
            commands.add(command)
            if (command.startsWith("dumpsys package ")) return ShellResult("  userId=$uid\n  pkgFlags=[ ${if (system) "SYSTEM" else "HAS_CODE"} ]\n  versionCode=$version minSdk=23\n  User 0: installed=true enabled=$state\n", 0)
            check(command.startsWith("pm disable-user "))
            state = 3
            return ShellResult("ok", 0)
        }
    }
    private fun engine(s: Fake, j: Journal = Journal(), protected: Set<String> = emptySet(), manual: Boolean = false) = DebloatEngine(s, j, 0, "pl.ambienthome.tv", protected, manual)
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalStateException) {} }
    @Test fun onlyExactAuditedDeviceHasTargets() {
        assertEquals(listOf(pkg), AutomaticDebloat.targets(device).map { it.pkg })
        for (other in listOf(device.copy(manufacturer="Other"), device.copy(model="MiTV-MOOR3"), device.copy(fingerprint=device.fingerprint+".OTA"))) assertTrue(AutomaticDebloat.targets(other).isEmpty())
    }
    @Test fun contentDemoAndCoreUidStatisticsAreNeverAutomatic() {
        for (p in listOf("com.xiaomi.statistic", "com.xm.webcontent", "com.mitv.tvhome.michannel", "com.mitv.tvhome.oemtab", "fusion.android.tv.demo", "com.google.android.videos")) assertNull(AutomaticDebloat.target(device,p))
    }
    @Test fun matchingVersionCanBeAutomaticallyDisabledAndJournaled() {
        val s=Fake(); val j=Journal(); val e=engine(s,j)
        assertTrue(e.automaticEligible(pkg,device)); assertTrue(j.rows.isEmpty()); assertFalse(s.commands.any { it.startsWith("pm ") })
        e.disableAutomatic(pkg,device); assertEquals(3,s.state); assertEquals("disabled",j.rows.single().phase)
    }
    @Test fun changedMissingOrInvalidVersionIsSkipped() {
        for (v in listOf("2023080918", "", "unknown", "99999999999999999999999")) {
            val s=Fake().apply { version=v }; val j=Journal(); val e=engine(s,j)
            assertFalse(e.automaticEligible(pkg,device)); rejects { e.disableAutomatic(pkg,device) }
            assertTrue(j.rows.isEmpty()); assertEquals(0,s.state)
        }
    }
    @Test fun versionIsRecheckedAfterPlanBeforeMutation() {
        val s=Fake(); val j=Journal(); val e=engine(s,j)
        assertTrue(e.automaticEligible(pkg,device)); s.version="2023080918"
        rejects { e.disableAutomatic(pkg,device) }; assertTrue(j.rows.isEmpty()); assertEquals(0,s.state)
    }
    @Test fun manualModeCannotExpandAutomaticTargetsOrDevices() {
        val s=Fake(); val e=engine(s,manual=true)
        assertFalse(e.automaticEligible("com.vendor.optional",device))
        rejects { e.disableAutomatic("com.vendor.optional",device) }
        rejects { e.disableAutomatic(pkg,device.copy(model="Unknown")) }
        assertTrue(s.commands.isEmpty())
    }
    @Test fun dynamicProtectionStillBlocksAutomaticCleanup() {
        val s=Fake(); val e=engine(s,protected=setOf(pkg))
        assertFalse(e.automaticEligible(pkg,device)); rejects { e.disableAutomatic(pkg,device) }; assertTrue(s.commands.isEmpty())
    }
    @Test fun coreUidAndNonSystemPackageAreSkipped() {
        for (s in listOf(Fake().apply { uid=1000 }, Fake().apply { system=false })) {
            val e=engine(s); assertFalse(e.automaticEligible(pkg,device)); rejects { e.disableAutomatic(pkg,device) }; assertEquals(0,s.state)
        }
    }
    @Test fun alreadyDisabledPackagesNeverBecomeAutomaticChanges() {
        for (state in 2..4) { val s=Fake().apply { this.state=state }; val j=Journal(); val e=engine(s,j); assertFalse(e.automaticEligible(pkg,device)); e.disableAutomatic(pkg,device); assertTrue(j.rows.isEmpty()); assertEquals(state,s.state) }
    }
    @Test fun pendingJournalRecordIsNotSelectedAgain() {
        val s=Fake(); val j=Journal(); j.put(DebloatChange(pkg,0,0,"planned",1))
        val e=engine(s,j); assertFalse(e.automaticEligible(pkg,device)); rejects { e.disableAutomatic(pkg,device) }; assertEquals(0,s.state)
    }
}
