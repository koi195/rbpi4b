# 19 — Running on an Android tablet (no root)

Target: a Samsung Galaxy Tab A7-class tablet (arm64, Android 10–13), no root, a
DDJ-FLX4 on the USB-C port through OTG.

> **Status: written, not run.** The C side compiles and the framebuffer bridge was
> exercised natively (see [What has been checked](#what-has-been-checked)). The
> Android app has **never been built or installed**: the environment it was written
> in has no Android SDK. Treat the first build as bring-up, not as a release. Where
> a number or behaviour below is an expectation rather than a measurement, it says so.

## How it works

There is still no emulation: the same ARM32 `rbp` and the same shims run, but inside
**proot** instead of a chroot, because an unrooted app cannot `chroot` or `mknod`.
Every Pi device the player needs is replaced by a file or a FIFO under the chroot's
`/tmp`, and the app sits on the other end of each one.

```
 Android app (Kotlin)                      proot  ->  rbx3-run  ->  rbp + shims
 ─────────────────────                     ──────────────────────────────────────
 FbSurface   ◄── mmap ──  rb_fb.raw        ◄── fbshim-android.so: /dev/fb0 is a file
 FbSurface   ─ evdev ──►  rb_touch.fifo    ──► pointsrc.c (ANDROID_BRIDGE)
 MidiBridge  ─ MIDI ───►  rb_midi_in.fifo  ──► knobshim.so (midi_io.c)
 MidiBridge  ◄─ MIDI ───  rb_midi_out.fifo ◄── LEDs, FLX4 keepalive
 AudioBridge ◄─ PCM ────  rb_audio.fifo    ◄── audioshim.so -> ALSA `file` plugin
 RbService   ── runs ───► start-android.sh ──► proot ... rbp
```

| Pi | Android |
| --- | --- |
| `/dev/fb0` (vc4) | `tmp/rb_fb.raw`, 1280×800 RGB565, `MAP_SHARED` by both sides |
| evdev touch / mouse | `tmp/rb_touch.fifo`, Linux `input_event` records, already in 1280×800 |
| ALSA sequencer, FLX4 | `android.media.midi`, bytes forwarded through two FIFOs |
| ALSA `hw:CARD=DDJFLX4` | ALSA `file` plugin → FIFO → `AudioTrack` (`scripts/android/asound.conf`) |
| `chroot`, `mount --bind` | `proot -r … -b …` |
| USB stick + udev | a folder bound as `/media/usb1/sda1`, announced through `udev_usb1` |
| `systemd` unit | foreground `Service` |

Files:

* `scripts/shims/android_env.h`, `android_bridge.c` — framebuffer-as-a-file
  (one interposed `syscall()`), and the `ANDROID_BRIDGE` switch.
* `scripts/shims/pointsrc.c`, `midi_io.c` — the touch and MIDI FIFO inputs. With
  `ANDROID_BRIDGE` unset they behave exactly as on the Pi.
* `scripts/android/start-android.sh`, `asound.conf` — launcher and audio device.
* `scripts/android/build-bundle.sh` — Pi tarball → Android bundle.
* `scripts/android/fetch-proot.sh` — arm64 proot + loaders + libtalloc.
* `android/` — the Gradle project. `.github/workflows/android.yml` builds it.

## What you need to provide

1. The extracted XDJ-RX3 assets and the patched DirectFB stack, exactly as for the Pi
   ([TUTORIAL.md](../TUTORIAL.md), steps 0–2). This repo ships none of it, and
   firmware acquisition is out of scope here as it is for the Pi.
2. A way to compile the Android APK: either Android Studio, or push the repo to GitHub
   and run the `android-apk` workflow.

## Build

```sh
# 1. As for the Pi: build the DirectFB stack and the chroot tarball.
RX3=/path/to/extracted DFB="$PWD/work/dfb" scripts/build-chroot.sh   # work/rbpi4b-pi4.tgz

# 2. Turn it into the Android bundle (also builds the Android shims).
scripts/android/build-bundle.sh                                      # work/rbpi4b-android.tgz

# 3. Get proot into the APK's native libraries, then build the APK.
scripts/android/fetch-proot.sh
cd android && gradle assembleDebug     # or open android/ in Android Studio
#   -> android/app/build/outputs/apk/debug/app-debug.apk
```

## Install and run

1. Install `app-debug.apk` (allow installs from the source you copied it with).
2. Copy `rbpi4b-android.tgz` to the tablet (it is large) and tap **Importar bundle…**.
3. Optional: **Importar export USB…** and pick a folder written by rekordbox's
   *Export to USB Device* (it must contain `PIONEER/`). It is copied into the app's
   storage and presented to the player as the stick. Without it the player starts but
   has no tracks, and so has nothing to play.
4. Plug the DDJ-FLX4 into the tablet's USB-C port (OTG adapter if needed) and allow
   the MIDI/USB prompt.
5. **▶ Iniciar.** The Back button toggles the control panel while the player runs.

The panel's first lines are a diagnosis: whether the device can run 32-bit ARM, whether
proot and its loaders are present, whether a bundle and an export are installed.

## Why `targetSdk` is 28

From `targetSdk` 29 Android's SELinux policy stops an app from executing code out of its
own data directory, and the whole player userland lives there. proot gets around the
*exec* by loading binaries itself, but the loaded `ld.so` and libraries are still mapped
executable from app data. Termux makes the same trade. The consequences: the APK is for
sideloading, it will show a "built for an older version of Android" notice, and it should
still install on current releases, as far as I know: Android 14 refuses installs
targeting below 23 and Android 15 below 24, and 28 clears both (not tested here).

## Known risks (none measured yet)

* **32-bit support in the kernel.** `rbp` is ARM32 and needs a kernel that runs 32-bit
  EL0 code. The panel reports `Build.SUPPORTED_32_BIT_ABIS`, which is the practical
  test. Devices that dropped 32-bit (many 2023+ SoCs) cannot run this at all.
* **Performance.** proot traces every syscall with `ptrace`; the Pi runs the player
  natively. `PROOT_NO_SECCOMP=1` is set because seccomp acceleration crashes proot on
  a number of Android builds, which makes this slower still. The player is not
  syscall-bound, but this is an expectation, not a number. A mid-range tablet may
  stutter; watch `rbp.log` and the frame pacing.
* **Phantom process killer (Android 12+).** Android may kill the player's child
  processes when the app is in the background. A foreground service helps; if the
  player dies after a while, the usual workaround is
  `adb shell settings put global settings_enable_monitor_phantom_procs false` (and
  `device_config put activity_manager max_phantom_processes 2147483647` on Android 12L+).
* **Audio latency.** The path is shim → FIFO (16 KiB, ≈31 ms) → `AudioTrack` → the
  tablet's mixer. Expect noticeably more latency than the FLX4's own USB audio on the
  Pi. Only the master pair is played; the headphone cue pair (channels 2–3) is read
  and discarded, because a tablet has one output.
* **MIDI.** The shim assumes the FLX4 map whatever the device is called
  (`ANDROID_MIDI_NAME`). The FLX4 tables are themselves unverified, see
  [15-flx4-midi.md](15-flx4-midi.md). LED rows are `-1` until seen to light, so no
  LEDs will light on Android either.
* **proot packaging.** `fetch-proot.sh` assumes how Termux lays out its `.deb` files and
  that its proot honours `PROOT_LOADER` / `PROOT_LOADER_32` outside Termux. Neither is
  checked. If proot reports a missing loader or `libtalloc.so.2`, that is where to look.
* **Not a target for the Play Store.** It carries a `targetSdk` of 28 and runs a
  third-party firmware's player; it is a personal interoperability build.

## What has been checked

* `android_bridge.c` compiles warning-free with the host compiler
  (`make -C scripts/shims android-check`).
* Preloaded into a small test program on x86-64 with `ANDROID_BRIDGE=1`, the bridge
  returned `1280×800`, 16 bpp, line length 2560, `smem_len` 2,048,000; created and
  sized the backing file; made writes through a `MAP_SHARED` mapping land in that file;
  paced `FBIOPAN_DISPLAY` at 60 Hz (30 pans in 500 ms); forwarded unrelated syscalls;
  and did nothing with `ANDROID_BRIDGE` unset.
* The shell scripts pass `sh -n`. The Kotlin sources parse (no syntax errors under the
  Kotlin 2.0 compiler); their Android API usage was **not** type-checked, because there
  is no Android SDK to check against. Expect to fix a compile error or two on first build.

## What has not

Everything that needs the tablet, the player or the controller: the APK build, proot
starting a 32-bit guest, `rbp` painting into the shared file, touch landing under a
finger, MIDI reaching the shim, audible audio, and the stick import. The order to check
them in is the Pi's own bring-up table in [13-raspberrypi4.md](13-raspberrypi4.md):
display first, then touch, then controls, then audio, then USB.

## Troubleshooting

| Symptom | Look at |
| --- | --- |
| Panel says `NO — rbp no puede correr aquí` | The device cannot execute 32-bit ARM. There is no fix inside the app. |
| `libproot: FALTA` | The APK was built without running `fetch-proot.sh` first. |
| Starts, then stops at once | **Ver log**: `app.log` (the launcher) and `rbp.log` (the player). |
| `cannot execute` / `Permission denied` for proot | `targetSdk` was changed above 28, or proot is not in `nativeLibraryDir`. |
| Black screen, player running | `tmp/rb_fb.raw` size must be 2,048,000; check `rbp.log` for DirectFB errors. |
| Taps do nothing | `tmp/pointsrc.log` in the chroot: does it say it opened the Android touch bridge? |
| No sound | `tmp/audioshim.log`: did it open `rbandroid`? Is the app's audio thread running (`app.log`)? |
| Controller ignored | `app.log` lines starting `midi:`; the controller must appear as a USB MIDI device. |
