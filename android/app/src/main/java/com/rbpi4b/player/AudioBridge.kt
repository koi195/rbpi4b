package com.rbpi4b.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Plays what the player's audio shim writes.
 *
 * The chain: audioshim.so -> ALSA device `rbandroid` (scripts/android/asound.conf,
 * a `file` plugin) -> /tmp/rb_audio.fifo -> this thread -> AudioTrack.
 *
 * The stream is 44.1 kHz, [channels] interleaved channels, S24_3LE (three bytes per
 * sample, little endian). Channels 0/1 are the master, 2/3 the headphone cue. A
 * tablet has one output, so only the master pair is played; the cue pair is read and
 * discarded so the pipe keeps draining.
 *
 * Pacing: AudioTrack.write() blocks until the device has room, this thread then
 * stops reading, the FIFO fills, and the shim's write blocks. The sound card's
 * clock therefore paces the player, which is the same job the FLX4's USB clock
 * does on the Pi.
 */
class AudioBridge(
    private val fifoPath: String,
    private val channels: Int,
    private val log: (String) -> Unit,
) : Thread("rb-audio") {

    @Volatile private var stopping = false

    init { isDaemon = true }

    fun shutdown() { stopping = true; interrupt() }

    override fun run() {
        if (!Fifo.waitFor(fifoPath, 30_000) { !stopping }) {
            log("audio: $fifoPath never appeared")
            return
        }
        // 16 KiB of pipe at 4 ch x 3 B x 44.1 kHz is ~31 ms: low enough to feel
        // attached to the controller, large enough not to underrun on a busy tablet.
        val reader = FifoReader.open(fifoPath, pipeBytes = 16384)
        if (reader == null) {
            log("audio: cannot open $fifoPath")
            return
        }

        val rate = 44100
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min * 2, 8192))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        log("audio: playing ($channels ch S24_3LE -> 2 ch S16, buffer ${track.bufferSizeInFrames} frames)")

        val frameBytes = channels * 3
        val raw = ByteArray(frameBytes * 1024)
        val pcm = ShortArray(1024 * 2)
        var have = 0                       // bytes of a partial frame carried over
        try {
            while (!stopping) {
                val n = reader.read(raw, 250, have, raw.size - have)
                if (n < 0) { sleep(50); continue }
                if (n == 0) continue
                val total = have + n
                val frames = total / frameBytes
                var o = 0
                for (f in 0 until frames) {
                    val b = f * frameBytes
                    // Top 16 bits of each 24-bit sample: bytes 1 and 2 (LE). The sign
                    // comes from byte 2 widened through Int, so it carries.
                    pcm[o++] = ((raw[b + 1].toInt() and 0xff) or (raw[b + 2].toInt() shl 8)).toShort()
                    pcm[o++] = ((raw[b + 4].toInt() and 0xff) or (raw[b + 5].toInt() shl 8)).toShort()
                }
                if (o > 0) track.write(pcm, 0, o)
                // Keep the tail of an incomplete frame for the next read.
                have = total - frames * frameBytes
                if (have > 0) System.arraycopy(raw, frames * frameBytes, raw, 0, have)
            }
        } catch (e: InterruptedException) {
        } finally {
            try { track.stop() } catch (e: IllegalStateException) { }
            track.release()
            reader.close()
            log("audio: stopped")
        }
    }
}
