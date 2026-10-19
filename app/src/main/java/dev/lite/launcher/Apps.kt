package dev.lite.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

class App(
    val pkg: String,
    val label: String,
    val intent: Intent,
    val banner: Drawable?,
    val icon: Drawable,
)

object Apps {
    /** All TV-launchable apps except this launcher, one entry per package. */
    fun load(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                App(
                    pkg = ai.packageName,
                    label = ri.loadLabel(pm).toString(),
                    intent = Intent(query)
                        .setClassName(ai.packageName, ai.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                    banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm),
                    icon = ri.loadIcon(pm),
                )
            }
    }

    /** Saved order first, then any new apps alphabetically. */
    fun sort(apps: List<App>, order: List<String>): List<App> {
        val rank = order.withIndex().associate { it.value to it.index }
        return apps.sortedWith(
            compareBy<App> { rank[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() }
        )
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var order: List<String>
        get() = sp.getString("order", "")!!.split(',').filter { it.isNotEmpty() }
        set(value) = sp.edit().putString("order", value.joinToString(",")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(value) = sp.edit().putStringSet("hidden", value).apply()
}
