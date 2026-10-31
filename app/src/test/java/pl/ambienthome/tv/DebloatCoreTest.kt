package pl.ambienthome.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DebloatCoreTest {
    private val pkg = "com.miui.tv.analytics"
    private class Journal : DebloatJournal {
        val rows = mutableListOf<DebloatChange>()
        var fail = false
        override fun entries() = rows.toList()
        override fun put(change: DebloatChange) { if (fail) throw IOException("disk full"); rows.removeAll { it.pkg == change.pkg && it.user == change.user }; rows.add(change) }
    }
    private class Fake(var state: Int = 0) : Shell {
        var uid: Int? = 10097
        var system = true
        var uncertain = false
        var beforeMutation: (() -> Unit)? = null
        val commands = mutableListOf<String>()
        override fun run(command: String): ShellResult {
            commands.add(command)
            if (command.startsWith("dumpsys")) return ShellResult("Package [example]:\n  ${uid?.let { "userId=$it" }.orEmpty()}\n  pkgFlags=[ ${if (system) "SYSTEM" else "HAS_CODE"} ]\n    User 10: installed=true enabled=3\n    User 0: installed=true hidden=false enabled=$state\n", 0)
            beforeMutation?.invoke()
            when {
                command.startsWith("pm disable-user") -> { state = 3; if (uncertain) throw IOException("connection lost after mutation") }
                command.startsWith("pm default-state") -> state = 0
                command.startsWith("pm enable") -> state = 1
                else -> error("Unexpected command: $command")
            }
            return ShellResult("ok", 0)
        }
    }
    private fun engine(s: Shell, j: Journal, protect: Set<String> = emptySet(), manual: Boolean = false) = DebloatEngine(s, j, 0, "pl.ambienthome.tv", protect, manual)
    private fun fails(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {} catch (_: IOException) {} }
    @Test fun defaultStateIsPersistedBeforeMutationAndExactlyRestored() {
        val s = Fake(); val j = Journal(); val e = engine(s,j)
        s.beforeMutation = { assertEquals("planned", j.rows.single().phase); assertEquals(0, j.rows.single().previous) }
        e.disable(pkg); assertEquals("disabled",j.rows.single().phase); assertEquals(3,s.state)
        s.beforeMutation = null; e.restore(j.rows.single())
        assertEquals(0,s.state); assertTrue(s.commands.contains("pm default-state --user 0 $pkg")); assertFalse(s.commands.any { it.startsWith("pm enable") })
    }
    @Test fun explicitlyEnabledStateUsesEnableOnRestore() { val s=Fake(1); val j=Journal(); val e=engine(s,j); e.disable(pkg); e.restore(j.rows.single()); assertEquals(1,s.state); assertTrue(s.commands.contains("pm enable --user 0 $pkg")) }
    @Test fun previouslyDisabledPackagesNeverEnterJournal() { for (state in 2..4) { val s=Fake(state); val j=Journal(); engine(s,j).disable(pkg); assertTrue(j.rows.isEmpty()); assertFalse(s.commands.any { it.startsWith("pm ") }) } }
    @Test fun journalFailurePreventsSystemMutation() { val s=Fake(); val j=Journal().apply { fail=true }; fails { engine(s,j).disable(pkg) }; assertEquals(0,s.state); assertFalse(s.commands.any { it.startsWith("pm ") }) }
    @Test fun lostConnectionAfterMutationRemainsRecoverable() { val s=Fake().apply { uncertain=true }; val j=Journal(); val e=engine(s,j); fails { e.disable(pkg) }; assertEquals("uncertain",j.rows.single().phase); assertEquals(3,s.state); s.uncertain=false; e.restore(j.rows.single()); assertEquals(0,s.state) }
    @Test fun plannedRecordAfterCrashCanRestore() { val j=Journal(); j.put(DebloatChange(pkg,0,0,"planned",1)); val s=Fake(3); engine(s,j).restore(j.rows.single()); assertEquals(0,s.state) }
    @Test fun secondDisableCannotOverwriteOriginalState() { val j=Journal(); val s=Fake(); val e=engine(s,j); e.disable(pkg); fails { e.disable(pkg) }; assertEquals(0,j.rows.single().previous) }
    @Test fun corePackagesAndIntegrationsAreBlocked() { for (p in listOf("com.google.android.gms","com.google.android.tungsten.setupwraith","com.google.android.apps.tv.launcherx","com.android.settings","com.mediatek.tvinput","dev.trooped.tvquickbars","flar2.homebutton","pl.ambienthome.tv","com.vendor.bluetooth","com.vendor.updateservice")) { val s=Fake(); fails { engine(s,Journal(),manual=true).disable(p) }; assertTrue(p,s.commands.isEmpty()) } }
    @Test fun activeUnknownAccessibilityServiceIsBlocked() { val s=Fake(); fails { engine(s,Journal(),setOf("com.vendor.access"),true).disable("com.vendor.access") }; assertTrue(s.commands.isEmpty()) }
    @Test fun arbitraryShellSyntaxCannotBeAValidPackage() { for (p in listOf("x.y;reboot","x.y/Activity","x.y\nreboot","--user","x.y $(id)","x.y|id")) { assertFalse(DebloatPolicy.validPackage(p)); val s=Fake(); fails { engine(s,Journal(),manual=true).disable(p) }; assertTrue(s.commands.isEmpty()) } }
    @Test fun unknownPackageNeedsExplicitManualMode() { val s=Fake(); fails { engine(s,Journal()).disable("com.vendor.optional") }; assertTrue(s.commands.isEmpty()); engine(s,Journal(),manual=true).disable("com.vendor.optional"); assertEquals(3,s.state) }
    @Test fun sharedCoreUidIsProtectedEvenInManualMode() { val s=Fake().apply { uid=1000 }; val j=Journal(); fails { engine(s,j,manual=true).disable("com.vendor.optional") }; assertTrue(j.rows.isEmpty()); assertEquals(0,s.state) }
    @Test fun missingUidFailsClosed() { val s=Fake().apply { uid=null }; fails { engine(s,Journal()).disable(pkg) }; assertEquals(0,s.state) }
    @Test fun nonSystemPackageFailsClosed() { val s=Fake().apply { system=false }; fails { engine(s,Journal()).disable(pkg) }; assertEquals(0,s.state) }
    @Test fun restoreRequiresOwnedRecordAndCorrectUser() { val j=Journal(); val s=Fake(3); val e=engine(s,j); fails { e.restore(DebloatChange(pkg,0,0,"planned",1)) }; val c=DebloatChange(pkg,0,10,"planned",1); j.put(c); fails { e.restore(c) }; assertEquals(3,s.state) }
    @Test fun externallyChangedDisabledStateIsNotOverridden() { val j=Journal(); j.put(DebloatChange(pkg,0,0,"disabled",1)); val s=Fake(2); fails { engine(s,j).restore(j.rows.single()) }; assertEquals(2,s.state); assertTrue(j.rows.single().pending) }
    @Test fun alreadyEnabledPackageIsNotMutatedByRestore() { val j=Journal(); j.put(DebloatChange(pkg,1,0,"disabled",1)); val s=Fake(0); engine(s,j).restore(j.rows.single()); assertFalse(s.commands.any { it.startsWith("pm ") }); assertEquals("restored",j.rows.single().phase) }
    @Test fun stateParserDoesNotConfuseOtherUsersOrFields() { assertEquals(0,DebloatEngine.parseState("User 10: enabled=3\nUser 0: installed=true enabled=0",0)); fails { DebloatEngine.parseState("User 10: enabled=3",0) }; fails { DebloatEngine.parseState("User 0: enabled=30",0) } }
}
