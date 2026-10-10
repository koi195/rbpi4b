/*
 * android_env.h — "are we running inside the Android app's proot?" and the three
 * FIFO paths the app talks to the shims over.
 *
 * Header-only on purpose: both fbshim.so (framebuffer + touch) and knobshim.so
 * (MIDI) need the answer, and a function defined in both objects would be two
 * copies of the same symbol in one global scope. `static inline` gives each its
 * own copy and no symbol for anyone to interpose, which is the same reason
 * syscalls.h is written this way.
 *
 * Off by default. With ANDROID_BRIDGE unset or 0 every shim behaves exactly as it
 * does on the Raspberry Pi; nothing in this file changes that.
 *
 * Everything the Android app and the shims share is a FILE under the chroot's
 * /tmp (which the app sees as <rootfs>/tmp):
 *
 *   /tmp/rb_fb.raw          RGB565 1280x800 framebuffer, MAP_SHARED by both sides
 *   /tmp/rb_touch.fifo      app -> shim   Linux input_event records (touch)
 *   /tmp/rb_midi_in.fifo    app -> shim   raw MIDI bytes from the controller
 *   /tmp/rb_midi_out.fifo   shim -> app   raw MIDI bytes to the controller (LEDs)
 *   /tmp/rb_audio.fifo      ALSA `file` PCM -> app   raw PCM (see asound.conf)
 */
#ifndef RBPI4B_ANDROID_ENV_H
#define RBPI4B_ANDROID_ENV_H

#include <stdlib.h>
#include <string.h>

static inline int android_mode(void)
{
    static int cached = -1;
    if (cached < 0) {
        const char *v = getenv("ANDROID_BRIDGE");
        cached = (v && v[0] && strcmp(v, "0") != 0) ? 1 : 0;
    }
    return cached;
}

static inline const char *android_env(const char *name, const char *dflt)
{
    const char *v = getenv(name);
    return (v && v[0]) ? v : dflt;
}

#define ANDROID_FB_FILE_DEFAULT     "/tmp/rb_fb.raw"
#define ANDROID_TOUCH_FIFO_DEFAULT  "/tmp/rb_touch.fifo"
#define ANDROID_MIDI_IN_DEFAULT     "/tmp/rb_midi_in.fifo"
#define ANDROID_MIDI_OUT_DEFAULT    "/tmp/rb_midi_out.fifo"

#endif /* RBPI4B_ANDROID_ENV_H */
