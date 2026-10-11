package dev.nercone.typefacedroplet.privilege

/** Which privilege the user chose. */
enum class PrivilegeChoice { AUTOMATIC, SHIZUKU, ROOT }

/** The privilege to use, with the status of every candidate for display. */
data class PrivilegeSelection(val privilege: Privilege?, val statuses: List<Pair<Privilege.Kind, PrivilegeStatus>>)

/**
 * Chooses the privilege to run the engine with. Automatically, Shizuku (and Sui, which
 * provides the Shizuku interface) is preferred over `su`, because it needs no shell and keeps
 * a single connection; `su` is used when Shizuku cannot run commands as root.
 */
class PrivilegeSelector(val shizuku: ShizukuPrivilege, val root: RootPrivilege) {
    suspend fun select(choice: PrivilegeChoice): PrivilegeSelection {
        val candidates = when (choice) {
            PrivilegeChoice.AUTOMATIC -> listOf(shizuku, root)
            PrivilegeChoice.SHIZUKU -> listOf(shizuku)
            PrivilegeChoice.ROOT -> listOf(root)
        }
        val statuses = mutableListOf<Pair<Privilege.Kind, PrivilegeStatus>>()
        for (candidate in candidates) {
            val status = candidate.status()
            statuses += candidate.kind to status
            if (status is PrivilegeStatus.Available) return PrivilegeSelection(candidate, statuses)
        }
        return PrivilegeSelection(null, statuses)
    }
}
