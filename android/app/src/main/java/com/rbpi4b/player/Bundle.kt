package com.rbpi4b.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/** Where everything lives, in one place. */
object Layout {
    fun root(ctx: Context) = File(ctx.filesDir, "rb")
    fun chroot(ctx: Context) = File(root(ctx), "rbx3-run")
    fun tmp(ctx: Context) = File(chroot(ctx), "tmp")
    fun logDir(ctx: Context) = File(root(ctx), "log")
    fun rbpLog(ctx: Context) = File(logDir(ctx), "rbp.log")
    fun appLog(ctx: Context) = File(logDir(ctx), "app.log")

    /** proot's scratch space. Must be on the same filesystem rules as the app's data. */
    fun prootTmp(ctx: Context) = File(ctx.cacheDir, "proot")

    /** Directory that holds symlinks the dynamic linker needs (libtalloc.so.2). */
    fun libLinks(ctx: Context) = File(ctx.filesDir, "lib")

    /**
     * The "USB stick". Bound into the chroot as /media/usb1/sda1. It lives in the
     * app's external files directory so it can be filled over MTP or `adb push`,
     * and falls back to internal storage when there is no external volume.
     */
    fun media(ctx: Context): File =
        (ctx.getExternalFilesDir("usb") ?: File(ctx.filesDir, "usb")).also { it.mkdirs() }

    fun nativeLib(ctx: Context, name: String) = File(ctx.applicationInfo.nativeLibraryDir, name)
}

object Shell {
    /** Runs a command line with Android's own shell and returns its combined output. */
    fun run(cmd: String): Pair<Int, String> {
        val p = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }
}

object BundleInstaller {

    fun isInstalled(ctx: Context): Boolean =
        File(Layout.root(ctx), "start-android.sh").isFile &&
            File(Layout.root(ctx), "rb.conf").isFile &&
            Layout.chroot(ctx).isDirectory

    /**
     * Unpacks a bundle produced by scripts/android/build-bundle.sh into
     * <files>/rb. It goes through Android's own `tar` (toybox) because that one
     * keeps symlinks and file modes, which a chroot tree is full of and which
     * java.util.zip-style extraction would flatten.
     *
     * The old install is replaced only after the new tree unpacked, and the
     * operator's rb.local.conf survives the replacement.
     */
    fun install(ctx: Context, src: Uri, log: (String) -> Unit): Boolean {
        val files = ctx.filesDir
        val fresh = File(files, "rb.new")
        val dest = Layout.root(ctx)

        log("Preparando…")
        Shell.run("rm -rf '${fresh.path}'")
        if (!fresh.mkdirs()) {
            log("No se pudo crear ${fresh.path}")
            return false
        }

        log("Extrayendo el bundle (puede tardar varios minutos)…")
        val proc = ProcessBuilder("/system/bin/sh", "-c", "exec /system/bin/tar -xzf - -C '${fresh.path}'")
            .redirectErrorStream(true)
            .start()

        val tarOut = StringBuilder()
        val drain = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (tarOut.length < 8000) tarOut.append(line).append('\n')
                }
            } catch (e: IOException) {
            }
        }
        drain.isDaemon = true
        drain.start()

        try {
            val input = ctx.contentResolver.openInputStream(src)
            if (input == null) {
                proc.destroy()
                log("No se pudo abrir el archivo elegido.")
                return false
            }
            input.use { i -> proc.outputStream.use { o -> i.copyTo(o, 1 shl 16) } }
        } catch (e: IOException) {
            proc.destroy()
            log("Error leyendo el bundle: ${e.message}")
            return false
        }
        val code = proc.waitFor()
        drain.join(2000)

        // tar exits non-zero for things that do not matter here (it cannot mknod a
        // device node without root, and the launcher recreates those anyway), so the
        // check that counts is whether the tree that comes out is the one we need.
        Shell.run("chmod -R u+rwX '${fresh.path}'")
        val ok = File(fresh, "start-android.sh").isFile &&
            File(fresh, "rb.conf").isFile &&
            File(fresh, "rbx3-run").isDirectory
        if (!ok) {
            log("El bundle no es válido (tar salió con $code).\n${tarOut.toString().take(1200)}")
            Shell.run("rm -rf '${fresh.path}'")
            return false
        }
        if (code != 0) log("Aviso: tar salió con $code, pero el árbol está completo.")

        // Carry the operator's overrides and logs across an upgrade.
        val keep = File(dest, "rb.local.conf")
        if (keep.isFile) Shell.run("cp '${keep.path}' '${fresh.path}/rb.local.conf'")

        Shell.run("rm -rf '${dest.path}'")
        if (!fresh.renameTo(dest)) {
            log("No se pudo mover ${fresh.path} a ${dest.path}")
            return false
        }
        Shell.run("chmod 755 '${dest.path}/start-android.sh'")
        File(dest, "log").mkdirs()
        log("Bundle instalado en ${dest.path}")
        return true
    }

    fun uninstall(ctx: Context) {
        Shell.run("rm -rf '${Layout.root(ctx).path}' '${File(ctx.filesDir, "rb.new").path}'")
    }
}

/** Copies a rekordbox export (the folder `Export to USB Device` writes) into the app. */
object MediaImporter {

    fun copyTree(ctx: Context, tree: Uri, dest: File, progress: (String) -> Unit): Int {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var count = 0
        fun walk(docId: String, into: File) {
            into.mkdirs()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = (c.getString(1) ?: continue).replace('/', '_')
                    if (name == "." || name == "..") continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    val out = File(into, name)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, out)
                    } else {
                        if (size > 0 && out.length() == size) continue // already copied
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        ctx.contentResolver.openInputStream(uri)?.use { i ->
                            out.outputStream().use { o -> i.copyTo(o, 1 shl 16) }
                        }
                        count++
                        if (count % 10 == 0) progress("Copiados $count archivos… ($name)")
                    }
                }
            }
        }
        walk(rootId, dest)
        return count
    }

    fun hasExport(ctx: Context): Boolean = File(Layout.media(ctx), "PIONEER").isDirectory
}

object Diagnostics {
    fun report(ctx: Context): String {
        val sb = StringBuilder()
        val abis = Build.SUPPORTED_ABIS.joinToString()
        sb.append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}\n")
        sb.append("ABIs: $abis\n")
        val has32 = Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()
        sb.append("Ejecuta ARM de 32 bits: ${if (has32) "sí" else "NO — rbp no puede correr aquí"}\n")
        val dir = ctx.applicationInfo.nativeLibraryDir
        fun lib(n: String) = if (File(dir, n).isFile) "ok" else "FALTA"
        sb.append("libproot: ${lib("libproot.so")} · loader: ${lib("libproot-loader.so")} · loader32: ${lib("libproot-loader32.so")} · talloc: ${lib("libtalloc.so")}\n")
        sb.append("Bundle: ${if (BundleInstaller.isInstalled(ctx)) "instalado" else "no instalado"}\n")
        val media = Layout.media(ctx)
        sb.append("Memoria USB: ${media.path} (${if (MediaImporter.hasExport(ctx)) "export rekordbox encontrado" else "sin PIONEER/"})\n")
        val free = ctx.filesDir.usableSpace / (1024 * 1024)
        sb.append("Espacio libre: $free MB")
        return sb.toString()
    }

    fun ready(ctx: Context): Boolean =
        BundleInstaller.isInstalled(ctx) &&
            Layout.nativeLib(ctx, "libproot.so").isFile &&
            Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()
}
