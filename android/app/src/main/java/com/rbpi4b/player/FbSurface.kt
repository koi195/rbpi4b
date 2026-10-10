package com.rbpi4b.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The player's screen, and the player's touch panel.
 *
 * Drawing: the shim (android_bridge.c) turns /dev/fb0 into a file, tmp/rb_fb.raw,
 * 1280x800 RGB565, one page. This view maps the same file and blits it into the
 * surface at the display's rate, aspect-fitted with black bars.
 *
 * Touch: every finger is translated into the Linux evdev records the shim's touch
 * reader already understands (multi-touch type B: ABS_MT_SLOT / TRACKING_ID /
 * POSITION_X/Y, plus ABS_X/Y and BTN_TOUCH for the primary contact), already in the
 * 1280x800 logical space, and written to tmp/rb_touch.fifo. The shim applies an
 * identity transform in Android mode, so what is computed here is what rbp sees.
 */
class FbSurface(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback {

    companion object {
        const val W = 1280
        const val H = 800
        const val LEN = W * H * 2

        private const val EV_SYN = 0
        private const val EV_KEY = 1
        private const val EV_ABS = 3
        private const val SYN_REPORT = 0
        private const val BTN_TOUCH = 0x14a
        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private const val SLOTS = 10
    }

    private val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    @Volatile private var scale = 1f
    @Volatile private var left = 0f
    @Volatile private var top = 0f
    @Volatile private var fbFile: File? = null
    @Volatile private var drawing = false
    private var thread: Thread? = null

    /** Where touch events go; set by [RbService] while the player runs. */
    @Volatile var touch: FifoWriter? = null

    init {
        holder.addCallback(this)
        holder.setFormat(android.graphics.PixelFormat.RGB_565)
        isFocusable = true
    }

    /** Point the view at the shared framebuffer file (it may not exist yet). */
    fun attach(file: File?) { fbFile = file }

    // ---- drawing ---------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        drawing = true
        val t = Thread({ renderLoop() }, "rb-fb")
        t.isDaemon = true
        thread = t
        t.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        val s = minOf(w / W.toFloat(), h / H.toFloat())
        scale = s
        left = (w - W * s) / 2f
        top = (h - H * s) / 2f
        dst.set(left, top, left + W * s, top + H * s)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        drawing = false
        thread?.join(500)
        thread = null
    }

    private fun mapFb(): MappedByteBuffer? {
        val f = fbFile ?: return null
        if (!f.isFile || f.length() < LEN) return null
        return try {
            RandomAccessFile(f, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, LEN.toLong())
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun renderLoop() {
        var map: MappedByteBuffer? = null
        while (drawing) {
            if (map == null) map = mapFb()
            val canvas = try { holder.lockCanvas() } catch (e: Exception) { null }
            if (canvas == null) {
                try { Thread.sleep(30) } catch (e: InterruptedException) { return }
                continue
            }
            try {
                canvas.drawColor(Color.BLACK)
                val m = map
                if (m != null) {
                    m.position(0)
                    bitmap.copyPixelsFromBuffer(m)
                    canvas.drawBitmap(bitmap, null, dst, paint)
                }
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) { }
            }
            // Waiting for the player: do not spin on lockCanvas.
            if (map == null) try { Thread.sleep(200) } catch (e: InterruptedException) { return }
        }
    }

    // ---- touch -----------------------------------------------------------------

    private val lastX = IntArray(SLOTS) { -1 }
    private val lastY = IntArray(SLOTS) { -1 }
    private val active = BooleanArray(SLOTS)
    private var trackingId = 0
    private val buf = ByteBuffer.allocate(16 * 160).order(ByteOrder.LITTLE_ENDIAN)

    private fun rec(type: Int, code: Int, value: Int) {
        // struct input_event of the 32-bit chroot: two 32-bit timeval fields, then
        // type, code, value. The shim ignores the timestamp.
        buf.putInt(0).putInt(0)
        buf.putShort(type.toShort()).putShort(code.toShort())
        buf.putInt(value)
    }

    private fun lx(x: Float) = ((x - left) / scale).toInt().coerceIn(0, W - 1)
    private fun ly(y: Float) = ((y - top) / scale).toInt().coerceIn(0, H - 1)

    private fun position(slot: Int, x: Int, y: Int) {
        rec(EV_ABS, ABS_MT_POSITION_X, x)
        rec(EV_ABS, ABS_MT_POSITION_Y, y)
        if (slot == 0) {
            rec(EV_ABS, ABS_X, x)
            rec(EV_ABS, ABS_Y, y)
        }
        lastX[slot] = x
        lastY[slot] = y
    }

    private fun press(slot: Int, e: MotionEvent, index: Int) {
        if (slot !in 0 until SLOTS) return
        rec(EV_ABS, ABS_MT_SLOT, slot)
        rec(EV_ABS, ABS_MT_TRACKING_ID, ++trackingId and 0xffff)
        position(slot, lx(e.getX(index)), ly(e.getY(index)))
        if (slot == 0) rec(EV_KEY, BTN_TOUCH, 1)
        active[slot] = true
    }

    private fun release(slot: Int) {
        if (slot !in 0 until SLOTS || !active[slot]) return
        rec(EV_ABS, ABS_MT_SLOT, slot)
        rec(EV_ABS, ABS_MT_TRACKING_ID, -1)
        if (slot == 0) rec(EV_KEY, BTN_TOUCH, 0)
        active[slot] = false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val out = touch ?: return true
        if (!out.isOpen) return true
        buf.clear()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                press(e.getPointerId(i), e, i)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val slot = e.getPointerId(i)
                    if (slot !in 0 until SLOTS || !active[slot]) continue
                    val x = lx(e.getX(i))
                    val y = ly(e.getY(i))
                    if (x == lastX[slot] && y == lastY[slot]) continue
                    rec(EV_ABS, ABS_MT_SLOT, slot)
                    position(slot, x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                for (s in 0 until SLOTS) release(s)
            }
            else -> return true
        }
        if (buf.position() == 0) return true
        rec(EV_SYN, SYN_REPORT, 0)
        out.write(buf.array(), 0, buf.position())
        return true
    }
}
