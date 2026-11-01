package pl.ambienthome.tv

data class DebloatDevice(val manufacturer: String, val model: String, val fingerprint: String)
data class AutomaticTarget(val pkg: String, val version: Long)

/** Deliberately no manufacturer-wide, package-name-only or unknown-build fallback. */
object AutomaticDebloat {
    private val observedDevice = DebloatDevice("Xiaomi", "MiTV-MOOR2", "Xiaomi/flipped/venom:11/RTM5.220609.003/2674:user/release-keys")
    // Observed already disabled during audit and subsequent use, not a fresh disable/restore test.
    // com.xiaomi.statistic uses a core UID on this firmware and is intentionally excluded.
    private val observedTargets = listOf(AutomaticTarget("com.miui.tv.analytics", 2023080917L))
    fun targets(device: DebloatDevice): List<AutomaticTarget> = if (device == observedDevice) observedTargets else emptyList()
    fun target(device: DebloatDevice, pkg: String): AutomaticTarget? = targets(device).singleOrNull { it.pkg == pkg }
    fun matches(target: AutomaticTarget, dump: String): Boolean {
        val version = Regex("(?m)^\\s*versionCode=(\\d+)\\b").find(dump)?.groupValues?.get(1)?.toLongOrNull()
        return version == target.version
    }
}
