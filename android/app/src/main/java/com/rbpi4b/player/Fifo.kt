package com.rbpi4b.player

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.FileDescriptor
import java.io.InterruptedIOException

/**
 * Thin wrappers over a named pipe that the launcher created.
 *
 * Both ends are opened O_RDWR on purpose. A FIFO opened read-only blocks until a
 * writer shows up, and one opened write-only blocks until a reader does; the
 * player's shims and this app start at different moments, so a plain open would
 * deadlock one side or the other. With O_RDWR the open never blocks and, because
 * this process then holds both ends, the pipe never reports EOF when the other
 * process restarts. (The shims do the same, see pointsrc.c and midi_io.c.)
 */
object Fifo {
    fun isFifo(path: String): Boolean = try {
        (Os.stat(path).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFIFO
    } catch (e: ErrnoException) {
        false
    }

    /** Waits for the launcher to create [path]. */
    fun waitFor(path: String, timeoutMs: Long, alive: () -> Boolean = { true }): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && alive()) {
            if (isFifo(path)) return true
            try { Thread.sleep(100) } catch (e: InterruptedException) { return false }
        }
        return isFifo(path)
    }

    /** Linux F_SETPIPE_SZ. */
    private const val F_SETPIPE_SZ = 1031

    fun open(path: String, nonBlock: Boolean, pipeBytes: Int = 0): FileDescriptor? = try {
        // Refuse anything that is not a FIFO: Os.open with O_RDWR on a missing path
        // would not create it, but a regular file here would be a wiring mistake.
        if (!isFifo(path)) {
            null
        } else {
            val flags = OsConstants.O_RDWR or (if (nonBlock) OsConstants.O_NONBLOCK else 0)
            val fd = Os.open(path, flags, 0)
            if (pipeBytes > 0) {
                try { Os.fcntlInt(fd, F_SETPIPE_SZ, pipeBytes) } catch (e: ErrnoException) { /* keep default */ }
            }
            fd
        }
    } catch (e: ErrnoException) {
        null
    }

    fun close(fd: FileDescriptor?) {
        if (fd == null) return
        try { Os.close(fd) } catch (e: ErrnoException) { /* nothing to do */ }
    }
}

/** Non-blocking writer: a stalled consumer drops data instead of stalling the UI. */
class FifoWriter {
    @Volatile private var fd: FileDescriptor? = null

    val isOpen: Boolean get() = fd != null

    fun open(path: String): Boolean {
        close()
        fd = Fifo.open(path, nonBlock = true)
        return fd != null
    }

    fun write(buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        val f = fd ?: return
        try {
            Os.write(f, buf, off, len)
        } catch (e: ErrnoException) {
            // EAGAIN (pipe full) or EPIPE: drop. For touch and LED traffic a lost
            // message is the right failure.
        } catch (e: InterruptedIOException) {
        }
    }

    fun close() {
        val f = fd
        fd = null
        Fifo.close(f)
    }
}

/** Reader with a poll() timeout so the owning thread can notice it should stop. */
class FifoReader(private val fd: FileDescriptor) {
    /** Reads into buf[off, off+len). Returns bytes read, 0 on timeout, -1 on error. */
    fun read(buf: ByteArray, timeoutMs: Int, off: Int = 0, len: Int = buf.size - off): Int {
        return try {
            val p = StructPollfd()
            p.fd = fd
            p.events = OsConstants.POLLIN.toShort()
            val n = Os.poll(arrayOf(p), timeoutMs)
            if (n <= 0) 0 else Os.read(fd, buf, off, len)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) 0 else -1
        } catch (e: InterruptedIOException) {
            0
        }
    }

    fun close() = Fifo.close(fd)

    companion object {
        fun open(path: String, pipeBytes: Int = 0): FifoReader? {
            val fd = Fifo.open(path, nonBlock = true, pipeBytes = pipeBytes) ?: return null
            return FifoReader(fd)
        }
    }
}

internal fun File.fifoPath(): String = absolutePath
