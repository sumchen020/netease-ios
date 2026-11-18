package me.naptila.neteaseios

import android.content.SharedPreferences
import me.naptila.neteaseios.bridge.InstalledGame

/** Local UI history, scoped to a package and its installed build. */
internal class PreparationHistory(private val prefs: SharedPreferences) {
    fun migratePreviousResult() {
        if (prefs.getBoolean("history_migrated", false)) return
        val pkg = prefs.getString("state_package", null)
        val state = prefs.getString("state", null)
        val op = prefs.getString("operation", null)
        val editor = prefs.edit()
        if (pkg != null && state != null && !prefs.contains("result_$pkg")) {
            val matched = op != null && op == prefs.getString("completed_operation", null)
            val history = when (state) {
                "submitted", "unconfirmed" -> if (matched) state else "preparing"
                else -> state
            }
            // Older records have no installation timestamp. Display them as history only.
            editor.putString("result_$pkg", history)
            prefs.getString("last_error", null)?.let { editor.putString("error_$pkg", it) }
        }
        editor.putBoolean("history_migrated", true).apply()
    }

    fun recentPackages(): List<String> =
        prefs.getString("recent_packages", "").orEmpty().split('|').filter { it.isNotEmpty() }

    fun rememberPackage(pkg: String) {
        val recent = (listOf(pkg) + recentPackages()).distinct().take(6)
        prefs.edit().putString("recent_packages", recent.joinToString("|")).apply()
    }

    fun begin(game: InstalledGame, attempt: String) {
        val pkg = game.spec.packageName
        prefs.edit().putString("attempt_$pkg", attempt)
            .putLong("build_$pkg", game.lastUpdateTime)
            .putString("result_$pkg", "preparing")
            .remove("error_$pkg").apply()
    }

    fun finish(pkg: String, attempt: String, result: String, error: String?): Boolean {
        if (prefs.getString("attempt_$pkg", null) != attempt || this.result(pkg) != "preparing") return false
        prefs.edit().putString("result_$pkg", result).putString("error_$pkg", error).apply()
        return true
    }

    fun confirmedPackages(installed: List<InstalledGame>): Set<String> {
        val recent = recentPackages().toSet()
        return installed.filter { it.spec.packageName in recent && result(it.spec.packageName) == "submitted" && sameBuild(it) }
            .map { it.spec.packageName }.toSet()
    }

    fun result(pkg: String): String? = prefs.getString("result_$pkg", null)
    fun error(pkg: String): String? = prefs.getString("error_$pkg", null)

    fun sameBuild(game: InstalledGame): Boolean =
        game.installed && game.lastUpdateTime > 0 &&
            prefs.getLong("build_${game.spec.packageName}", 0) == game.lastUpdateTime
}
