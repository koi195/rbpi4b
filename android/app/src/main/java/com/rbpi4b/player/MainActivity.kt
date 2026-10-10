package com.rbpi4b.player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * One screen: the player full-bleed, with a control panel over it.
 *
 * The panel is shown while the player is stopped, and toggled with the system Back
 * button while it runs (the player's own UI uses the whole 1280x800 surface, so
 * there is no spare corner to put a button in).
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_BUNDLE = 1
        const val REQ_MEDIA = 2
    }

    private lateinit var surface: FbSurface
    private lateinit var panel: View
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        surface = FbSurface(this)
        surface.attach(File(Layout.tmp(this), "rb_fb.raw"))
        surface.touch = RbService.touch
        root.addView(surface, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        panel = buildPanel()
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        RbService.onState = {
            runOnUiThread {
                refresh()
                if (RbService.running) {
                    // The player just came up: get out of its way.
                    panel.postDelayed({
                        if (RbService.running && !logView.isShown) panel.visibility = View.GONE
                    }, 1500)
                }
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        refresh()
    }

    override fun onDestroy() {
        if (RbService.onState != null) RbService.onState = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (RbService.running) {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (panel.visibility == View.VISIBLE) refresh()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---- panel -------------------------------------------------------------------

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            setBackgroundColor(Color.argb(235, 18, 20, 24))
        }
        col.addView(TextView(this).apply {
            text = "rbpi4b · rekordbox en Android"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        status = TextView(this).apply {
            setTextColor(Color.rgb(200, 205, 212))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, 20, 0, 20)
        }
        col.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("1 · Importar bundle…") { pick(REQ_BUNDLE, false) })
        row1.addView(button("2 · Importar export USB (rekordbox)…") { pick(REQ_MEDIA, true) })
        col.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = button("▶ Iniciar") { startPlayer() }
        stopBtn = button("■ Parar") { RbService.stop(this); }
        row2.addView(startBtn)
        row2.addView(stopBtn)
        row2.addView(button("Ver log") { toggleLog() })
        row2.addView(button("Ocultar panel") { panel.visibility = View.GONE })
        col.addView(row2)

        logView = TextView(this).apply {
            setTextColor(Color.rgb(160, 220, 170))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            visibility = View.GONE
        }
        col.addView(ScrollView(this).apply {
            addView(logView)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val running = RbService.running
        status.text = Diagnostics.report(this) + "\nEstado: " + (if (running) "EN MARCHA" else "parado")
        startBtn.isEnabled = !busy && !running
        stopBtn.isEnabled = running
        if (!running && panel.visibility != View.VISIBLE && !busy) panel.visibility = View.VISIBLE
        if (logView.isShown) showLog()
    }

    private fun toggleLog() {
        if (logView.visibility == View.VISIBLE) {
            logView.visibility = View.GONE
        } else {
            logView.visibility = View.VISIBLE
            showLog()
        }
    }

    private fun showLog() {
        fun tail(f: File, n: Int): String =
            if (!f.isFile) "(sin $f)" else f.readLines().takeLast(n).joinToString("\n")
        logView.text = "── app.log ──\n" + tail(Layout.appLog(this), 25) +
            "\n\n── rbp.log ──\n" + tail(Layout.rbpLog(this), 40)
    }

    private fun startPlayer() {
        if (!Diagnostics.ready(this)) {
            Toast.makeText(this, "Falta el bundle o proot; mira el estado de arriba.", Toast.LENGTH_LONG).show()
            return
        }
        RbService.start(this)
        status.postDelayed({ refresh() }, 500)
    }

    // ---- importing -----------------------------------------------------------------

    private fun pick(req: Int, tree: Boolean) {
        val i = if (tree) {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        }
        startActivityForResult(i, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK || busy) return
        if (RbService.running && requestCode == REQ_BUNDLE) {
            Toast.makeText(this, "Para el reproductor antes de reinstalar.", Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        refresh()
        Thread {
            val msg = when (requestCode) {
                REQ_BUNDLE -> {
                    val ok = BundleInstaller.install(this, uri) { say(it) }
                    if (ok) "Bundle instalado." else "La instalación falló; mira el log."
                }
                else -> {
                    try {
                        val n = MediaImporter.copyTree(this, uri, Layout.media(this)) { say(it) }
                        "Copiados $n archivos a la memoria USB virtual."
                    } catch (e: Exception) {
                        "La copia falló: ${e.message}"
                    }
                }
            }
            busy = false
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun say(s: String) {
        runOnUiThread { status.text = s }
    }
}
