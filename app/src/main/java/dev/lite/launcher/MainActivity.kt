package dev.lite.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var grid: GridLayout
    private lateinit var scroll: ScrollView
    private lateinit var title: TextView
    private lateinit var prefs: Prefs

    private var apps: List<App> = emptyList()
    private val visible = mutableListOf<App>()
    private var moving: App? = null

    private val io = Executors.newSingleThreadExecutor()
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        grid = findViewById(R.id.grid)
        scroll = findViewById(R.id.scroll)
        title = findViewById(R.id.title)
        prefs = Prefs(this)

        findViewById<View>(R.id.settings).setOnClickListener { launch(Intent(Settings.ACTION_SETTINGS)) }
        findViewById<View>(R.id.hidden).setOnClickListener { showHidden() }
        listOf(R.id.settings, R.id.hidden).forEach { id ->
            findViewById<View>(id).setOnFocusChangeListener { v, focused ->
                if (focused) title.text = v.contentDescription
            }
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        reload()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        io.shutdown()
        super.onDestroy()
    }

    // Home pressed while already home: jump back to the first app.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (moving != null) endMove()
        scroll.scrollTo(0, 0)
        grid.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen has nowhere to go back to")
    override fun onBackPressed() {
        if (moving != null) endMove()
    }

    private fun reload() {
        io.execute {
            val loaded = Apps.load(this)
            runOnUiThread {
                apps = Apps.sort(loaded, prefs.order)
                render((currentFocus?.tag as? App)?.pkg)
            }
        }
    }

    private fun render(focusPkg: String?) {
        val hidden = prefs.hidden
        visible.clear()
        visible += apps.filterNot { it.pkg in hidden }
        grid.removeAllViews()
        visible.forEach { grid.addView(tile(it)) }

        val target = visible.indexOfFirst { it.pkg == focusPkg }.coerceAtLeast(0)
        grid.getChildAt(target)?.requestFocus()
    }

    private fun tile(app: App): View {
        val v = LayoutInflater.from(this).inflate(R.layout.tile, grid, false)
        v.tag = app
        v.clipToOutline = true
        val banner = v.findViewById<ImageView>(R.id.banner)
        if (app.banner != null) {
            banner.setImageDrawable(app.banner)
            banner.visibility = View.VISIBLE
            v.findViewById<View>(R.id.fallback).visibility = View.GONE
        } else {
            v.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            v.findViewById<TextView>(R.id.label).text = app.label
        }

        v.setOnFocusChangeListener { view, focused ->
            val scale = if (focused) 1.1f else 1f
            view.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) 8f else 0f).setDuration(120).start()
            if (focused) title.text = if (moving === app) getString(R.string.move_hint) else app.label
        }
        v.setOnClickListener { if (moving != null) endMove() else launch(app.intent) }
        v.setOnLongClickListener { if (moving == null) showMenu(app); true }
        return v
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(app: App) {
        val actions = arrayOf(
            getString(R.string.move), getString(R.string.hide),
            getString(R.string.app_info), getString(R.string.uninstall),
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(app.label)
            .setItems(actions) { _, which ->
                val uri = Uri.fromParts("package", app.pkg, null)
                when (which) {
                    0 -> startMove(app)
                    1 -> {
                        prefs.hidden = prefs.hidden + app.pkg
                        render(null)
                    }
                    2 -> launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                    3 -> launch(Intent(Intent.ACTION_DELETE, uri))
                }
            }
            .show()
    }

    private fun showHidden() {
        val hidden = apps.filter { it.pkg in prefs.hidden }
        if (hidden.isEmpty()) {
            Toast.makeText(this, R.string.no_hidden, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.unhide)
            .setItems(hidden.map { it.label }.toTypedArray()) { _, which ->
                val app = hidden[which]
                prefs.hidden = prefs.hidden - app.pkg
                render(app.pkg)
            }
            .show()
    }

    private fun tileOf(app: App): View? = (0 until grid.childCount).map { grid.getChildAt(it) }.find { it.tag === app }

    private fun startMove(app: App) {
        moving = app
        tileOf(app)?.isSelected = true
        title.setText(R.string.move_hint)
    }

    private fun endMove() {
        val app = moving ?: return
        moving = null
        tileOf(app)?.isSelected = false
        title.text = app.label
        val hiddenPkgs = apps.map { it.pkg }.filter { it in prefs.hidden }
        prefs.order = visible.map { it.pkg } + hiddenPkgs
        apps = Apps.sort(apps, prefs.order)
    }

    // While moving, D-pad shifts the tile instead of the focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = moving ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val from = visible.indexOf(app)
        val cols = grid.columnCount
        val to = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> from - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> from + 1
            KeyEvent.KEYCODE_DPAD_UP -> from - cols
            KeyEvent.KEYCODE_DPAD_DOWN -> from + cols
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                endMove()
                return true
            }
            else -> return true
        }
        if (to in visible.indices) {
            visible.removeAt(from)
            visible.add(to, app)
            val v = grid.getChildAt(from)
            grid.removeViewAt(from)
            grid.addView(v, to)
            v.requestFocus()
        }
        return true
    }
}
