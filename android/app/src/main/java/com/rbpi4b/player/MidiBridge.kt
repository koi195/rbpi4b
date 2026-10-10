package com.rbpi4b.player

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * USB MIDI controller <-> the shim's two FIFOs.
 *
 *   controller --android.media.midi--> rb_midi_in.fifo  --> knobshim (buttons, jog, faders)
 *   controller <--android.media.midi-- rb_midi_out.fifo <-- knobshim (LEDs, FLX4 keepalive)
 *
 * The bytes are forwarded unchanged: the shim parses a raw MIDI stream
 * (midi_io.c, android_midi_poll) exactly as it would the ALSA sequencer's.
 *
 * Which device: the first USB MIDI device that is not the phone's own. The shim
 * assumes the DDJ-FLX4 map whatever the name says (ANDROID_MIDI_NAME), so plugging
 * in a different controller will not map correctly; see docs/15-flx4-midi.md.
 */
class MidiBridge(
    private val ctx: Context,
    private val inFifo: String,
    private val outFifo: String,
    private val log: (String) -> Unit,
) {
    private val manager = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val handler = Handler(Looper.getMainLooper())
    private val toShim = FifoWriter()

    @Volatile private var reader: Thread? = null
    @Volatile private var stopping = false
    private var device: MidiDevice? = null
    private var outPort: MidiOutputPort? = null
    private var inPort: MidiInputPort? = null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { attach(info) }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id) detach("controller unplugged")
        }
    }

    val connected: Boolean get() = device != null

    fun start() {
        val m = manager
        if (m == null) {
            log("midi: this device has no MIDI service")
            return
        }
        if (!Fifo.waitFor(inFifo, 30_000) { !stopping } || !Fifo.waitFor(outFifo, 5_000) { !stopping }) {
            log("midi: FIFOs never appeared")
            return
        }
        toShim.open(inFifo)
        m.registerDeviceCallback(callback, handler)
        m.devices.forEach { attach(it) }
        if (device == null) log("midi: no USB MIDI controller yet — plug it in (OTG)")
    }

    private fun name(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device ${info.id}"

    private fun attach(info: MidiDeviceInfo) {
        if (device != null || stopping) return
        if (info.type != MidiDeviceInfo.TYPE_USB) return
        if (info.outputPortCount == 0 && info.inputPortCount == 0) return
        val m = manager ?: return
        log("midi: opening '${name(info)}'")
        m.openDevice(info, { dev ->
            if (dev == null) {
                log("midi: could not open '${name(info)}'")
                return@openDevice
            }
            if (stopping || device != null) {
                dev.close()
                return@openDevice
            }
            device = dev
            // "Output port" is the device's output: data coming FROM the controller.
            outPort = if (info.outputPortCount > 0) dev.openOutputPort(0) else null
            outPort?.connect(object : MidiReceiver() {
                override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    toShim.write(data, offset, count)
                }
            })
            inPort = if (info.inputPortCount > 0) dev.openInputPort(0) else null
            log("midi: '${name(info)}' connected (in ${info.outputPortCount}, out ${info.inputPortCount})")
            startReturnPath()
        }, handler)
    }

    /** LED / keepalive traffic from the shim back to the controller. */
    private fun startReturnPath() {
        if (reader != null) return
        val t = Thread({
            val r = FifoReader.open(outFifo)
            if (r == null) {
                log("midi: cannot open $outFifo")
                return@Thread
            }
            val buf = ByteArray(4096)
            try {
                while (!stopping) {
                    val n = r.read(buf, 250)
                    if (n < 0) { Thread.sleep(50); continue }
                    if (n == 0) continue
                    try {
                        inPort?.send(buf, 0, n)
                    } catch (e: Exception) {
                        // The controller went away between the read and the send.
                    }
                }
            } catch (e: InterruptedException) {
            } finally {
                r.close()
            }
        }, "rb-midi-out")
        t.isDaemon = true
        reader = t
        t.start()
    }

    private fun detach(why: String) {
        log("midi: $why")
        try { outPort?.close() } catch (e: Exception) { }
        try { inPort?.close() } catch (e: Exception) { }
        try { device?.close() } catch (e: Exception) { }
        outPort = null
        inPort = null
        device = null
    }

    fun stop() {
        stopping = true
        manager?.unregisterDeviceCallback(callback)
        detach("stopped")
        reader?.interrupt()
        reader = null
        toShim.close()
    }
}
