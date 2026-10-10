package com.rbpi4b.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/**
 * Runs scripts/android/start-android.sh (which runs the player under proot) and
 * owns the three bridges that connect it to the tablet: audio, MIDI and touch.
 *
 * It is a foreground service because the player must keep running, and keep
 * feeding the sound card, while the screen is off or another app is in front.
 */
class RbService : Service() {

    companion object {
        const val ACTION_START = "com.rbpi4b.player.START"
        const val ACTION_STOP = "com.rbpi4b.player.STOP"
        private const val CHANNEL = "rb"
        private const val NOTIF_ID = 1

        /** The touch FIFO writer; [FbSurface] writes through it. */
        val touch = FifoWriter()

        @Volatile var running = false
            private set

        /** Called on a worker thread whenever [running] changes. */
        @Volatile var onState: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, RbService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RbService::class.java).setAction(ACTION_STOP))
        }
    }

    private var proc: Process? = null
    private var audio: AudioBridge? = null
    private var midi: MidiBridge? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            else -> launch()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun log(msg: String) {
        try {
            val f = Layout.appLog(this)
            f.parentFile?.mkdirs()
            if (f.length() > 512 * 1024) f.delete()
            f.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (e: IOException) {
        }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "rbpi4b", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("rbpi4b")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    @Synchronized
    private fun launch() {
        startForeground(NOTIF_ID, notification("Iniciando…"))
        if (proc != null) return

        val root = Layout.root(this)
        val script = File(root, "start-android.sh")
        if (!script.isFile) {
            log("start: $script missing — import the bundle first")
            stopSelf()
            return
        }
        val proot = Layout.nativeLib(this, "libproot.so")
        if (!proot.isFile) {
            log("start: ${proot.path} missing — build the APK after scripts/android/fetch-proot.sh")
            stopSelf()
            return
        }

        // proot is dynamically linked against libtalloc.so.2, which Android will not
        // install under that name (only lib*.so). A symlink in a directory we put on
        // LD_LIBRARY_PATH gives the linker the name it asks for.
        val links = Layout.libLinks(this).also { it.mkdirs() }
        val talloc = Layout.nativeLib(this, "libtalloc.so")
        if (talloc.isFile) {
            val link = File(links, "libtalloc.so.2")
            try {
                try { Os.remove(link.absolutePath) } catch (e: ErrnoException) { /* none yet */ }
                Os.symlink(talloc.absolutePath, link.absolutePath)
            } catch (e: ErrnoException) {
                log("start: cannot link libtalloc: ${e.message}")
            }
        }

        killRecorded(root, "stale proot from a previous run")

        val prootTmp = Layout.prootTmp(this).also { it.mkdirs() }
        File(root, "log").mkdirs()

        val pb = ProcessBuilder("/system/bin/sh", script.absolutePath)
            .directory(root)
            .redirectErrorStream(true)
        val env = pb.environment()
        env["RB_ROOT"] = root.absolutePath
        env["RB_PROOT"] = proot.absolutePath
        env["RB_TMP"] = prootTmp.absolutePath
        val loader = Layout.nativeLib(this, "libproot-loader.so")
        if (loader.isFile) env["RB_PROOT_LOADER"] = loader.absolutePath
        val loader32 = Layout.nativeLib(this, "libproot-loader32.so")
        if (loader32.isFile) env["RB_PROOT_LOADER32"] = loader32.absolutePath
        val inheritedLd: String = System.getenv("LD_LIBRARY_PATH") ?: ""
        env["LD_LIBRARY_PATH"] = "${links.absolutePath}:$inheritedLd"
        val media = Layout.media(this)
        if (MediaImporter.hasExport(this)) env["RB_ANDROID_MEDIA_DIR"] = media.absolutePath
        env["RB_ANDROID_AUDIO_CHANNELS"] = "4"

        val p = try {
            pb.start()
        } catch (e: IOException) {
            log("start: cannot run the launcher: ${e.message}")
            stopSelf()
            return
        }
        proc = p
        running = true
        onState?.invoke()
        updateNotification("En marcha")

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rbpi4b:player")
            .also { it.acquire() }

        // The launcher's own output (not rbp's — that goes to rbp.log).
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { log("sh: $it") }
            } catch (e: IOException) {
            }
        }, "rb-sh-log").apply { isDaemon = true }.start()

        // Bridges: each waits by itself for the FIFOs the launcher creates.
        val tmp = Layout.tmp(this)

        audio = AudioBridge(File(tmp, "rb_audio.fifo").absolutePath, 4, this::log).also { it.start() }
        midi = MidiBridge(
            this,
            File(tmp, "rb_midi_in.fifo").absolutePath,
            File(tmp, "rb_midi_out.fifo").absolutePath,
            this::log,
        )
        Thread({
            val touchPath = File(tmp, "rb_touch.fifo").absolutePath
            if (Fifo.waitFor(touchPath, 30_000) { proc != null }) touch.open(touchPath)
            midi?.start()
        }, "rb-bridges").apply { isDaemon = true }.start()

        // When the launcher ends — by us, or because rbp died — clean up.
        Thread({
            val code = try { p.waitFor() } catch (e: InterruptedException) { -1 }
            log("start-android.sh exited with $code")
            shutdown()
        }, "rb-wait").apply { isDaemon = true }.start()
    }

    private fun recordedPid(root: File): Int =
        try { File(root, "rb.pid").readText().trim().toInt() } catch (e: Exception) { -1 }

    /** Kills whatever the last run left behind, if its pid file still points at a live process. */
    private fun killRecorded(root: File, what: String) {
        val pid = recordedPid(root)
        if (pid <= 1) return
        try {
            Os.kill(pid, 0)                       // alive and ours?
            log("start: killing $what (pid $pid)")
            Os.kill(pid, OsConstants.SIGTERM)
            Thread.sleep(500)
            try { Os.kill(pid, OsConstants.SIGKILL) } catch (e: ErrnoException) { }
        } catch (e: ErrnoException) {
            // ESRCH: nothing there; EPERM: a recycled pid that is not ours. Leave it.
        }
        File(root, "rb.pid").delete()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    @Synchronized
    private fun shutdown() {
        val p = proc
        proc = null
        if (p != null) {
            // NOT p.destroy(): on Android that is SIGKILL, so the launcher's trap
            // would never run and proot (and the player under it) would be orphaned.
            // The launcher records proot's pid in rb.pid; SIGTERM that. proot then
            // kills the player (--kill-on-exit), the launcher's wait loop ends by
            // itself, and the process below is only the backstop.
            val pid = recordedPid(Layout.root(this))
            if (pid > 0) {
                try { Os.kill(pid, OsConstants.SIGTERM) } catch (e: ErrnoException) { }
            }
            Thread {
                try { Thread.sleep(4000) } catch (e: InterruptedException) { }
                if (pid > 0) {
                    try { Os.kill(pid, OsConstants.SIGKILL) } catch (e: ErrnoException) { }
                }
                if (p.isAlive) p.destroy()
            }.apply { isDaemon = true }.start()
        }
        audio?.shutdown(); audio = null
        midi?.stop(); midi = null
        touch.close()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        val was = running
        running = false
        if (was) onState?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
