#!/system/bin/sh
# start-android.sh — launch the XDJ-RX3 rekordbox player inside the Android app,
# with no root, through proot. The Android counterpart of scripts/device/start-rb.sh.
#
# Run by the app (RbService) with /system/bin/sh. It is deliberately plain POSIX sh
# because that is what Android ships (mksh/toybox).
#
# What the app must provide in the environment:
#   RB_ROOT       deploy root: contains rbx3-run/ (the chroot), rb.conf, log/
#   RB_PROOT      path of the proot executable (the app's native library dir)
#   RB_PROOT_LOADER  path of proot's loader, if the build has a separate one
#   RB_PROOT_LOADER32  the 32-bit loader. REQUIRED for this player: rbp is an ARM32
#                  binary and the proot is 64-bit, so proot injects the 32-bit loader
#                  into every guest process (PROOT_LOADER_32).
#   RB_TMP        a writable directory for proot's own temporary files
# Optional:
#   RB_ANDROID_MEDIA_DIR   a directory holding a rekordbox export (PIONEER/…);
#                          it is bound into the chroot as the USB stick.
#   RB_ANDROID_MEDIA_LABEL the stick's volume label shown in rbp (default RBEXPORT)
#   RB_ANDROID_AUDIO_CHANNELS  2 or 4 (default 4)
#
# Everything the Pi launcher does that needs root (modprobe, mount --bind of /dev,
# udev FIFOs from a udev rule, systemctl) is either not needed here or replaced by
# the FIFOs below. See docs/19-android.md for the full mapping.

set -u

: "${RB_ROOT:?RB_ROOT is not set}"
: "${RB_PROOT:?RB_PROOT is not set}"
: "${RB_TMP:?RB_TMP is not set}"

# --- configuration --------------------------------------------------------------
# Paths go in BEFORE rb.conf is sourced (its lines are `: "${VAR:=default}"`, so a
# non-empty value already in the environment wins).
export RB_DEPLOY_ROOT="$RB_ROOT"
export RB_CHROOT="$RB_ROOT/rbx3-run"
export RB_LOG_DIR="$RB_ROOT/log"

CONF="$RB_ROOT/rb.conf"
[ -f "$CONF" ] || { echo "start-android: $CONF not found (bundle not installed?)" >&2; exit 1; }
if [ -f "$RB_ROOT/rb.local.conf" ]; then . "$RB_ROOT/rb.local.conf"; fi
. "$CONF"

# The Android overrides go in AFTER, and are assigned rather than defaulted: `:=`
# treats an EMPTY value as unset, so an empty override placed before the source
# would be silently replaced by rb.conf's default (RB_AUDIO_MIRROR_DEV="" would
# come back as the Pi's HDMI device).
RB_POINT_CURSOR=0                               # a touch screen needs no arrow
RB_AUDIO_DEV=rbandroid                          # asound.conf's file->FIFO device
RB_AUDIO_CHANNELS="${RB_ANDROID_AUDIO_CHANNELS:-4}"
RB_AUDIO_FMT=s24_3le
RB_AUDIO_MIRROR_DEV=""                          # no HDMI mirror on a tablet
RB_MIDI_MAP="${RB_ANDROID_MIDI_MAP:-flx4}"
RB_EVDEV_MAP=none                               # no /dev/input in proot
RB_SCHED_RT=0                                   # SCHED_FIFO needs root
RB_NETALIAS=0
# Display: same as the verified Pi -- a 1280x800 RGB565 one-page fb, so the same
# present path (`off`, auto-upgraded to `convert` for the 32 bpp lie).
RB_FB_DEV=/dev/fb0

CH="$RB_CHROOT"
[ -d "$CH" ] || { echo "start-android: chroot $CH missing" >&2; exit 1; }
mkdir -p "$RB_LOG_DIR" "$RB_TMP"
RBP_LOG="$RB_LOG_DIR/rbp.log"
[ -f "$RBP_LOG" ] && mv -f "$RBP_LOG" "$RBP_LOG.prev"

echo "start-android: chroot $CH"
echo "start-android: proot  $RB_PROOT"

# --- the files and FIFOs shared with the app ---------------------------------------
# All of them live in the chroot's /tmp, which the app sees as $CH/tmp.
T="$CH/tmp"
mkdir -p "$T" "$CH/dev" "$CH/dev/shm" "$CH/media/usb1/sda1" "$CH/media/usb2/sda1"

mk_fifo() {
    [ -p "$1" ] && return 0
    rm -f "$1"
    mkfifo "$1" || { echo "start-android: cannot create FIFO $1" >&2; exit 1; }
    chmod 666 "$1" 2>/dev/null
}
mk_fifo "$T/rb_touch.fifo"
mk_fifo "$T/rb_midi_in.fifo"
mk_fifo "$T/rb_midi_out.fifo"
mk_fifo "$T/rb_audio.fifo"
# rbp's DeviceSQL / media notification channels (the udev rule's job on the Pi).
for f in udev_usb1 udev_usb2 udev_usbctn1 udev_usbctn2; do mk_fifo "$T/$f"; done
rm -f "$T/guard_LocalDBServer" "$T/req_LocalDBServer" "$T/knobshim.log" "$T/audioshim.log"

# The framebuffer file is created and sized by the shim on first open, but the app
# may map it before that, so make sure it exists at the right size now.
FB="$T/rb_fb.raw"
if [ ! -f "$FB" ] || [ "$(wc -c < "$FB")" != "2048000" ]; then
    rm -f "$FB"
    dd if=/dev/zero of="$FB" bs=1024 count=2000 2>/dev/null     # 2,048,000 bytes
fi

# Device stubs rbp opens (fix-dev.sh's list). FIFOs stand in for the SPI ports.
for d in subucom_spi1.0 subucom_spi2.0 subucom_spi_rdy3.0 subucom_spi_rdy4.0 hidg0; do
    mk_fifo "$CH/dev/$d"
done
for d in printkdrv0 tsc2007_2-0048 gpiodrv; do
    [ -e "$CH/dev/$d" ] || : > "$CH/dev/$d"
done
rm -f "$CH/dev/paudiog0"
ln -sf /proc/mounts "$CH/etc/mtab" 2>/dev/null

# --- chroot-side configuration ------------------------------------------------------
cp "$(dirname "$0")/asound.conf" "$CH/etc/asound.conf" 2>/dev/null || \
    cp "$RB_ROOT/asound.conf" "$CH/etc/asound.conf"
for rc in "$CH/usr/etc/directfbrc" "$CH/etc/directfbrc"; do
    [ -f "$rc" ] || continue
    for opt in no-vt no-vt-switch; do
        grep -qx "$opt" "$rc" || echo "$opt" >> "$rc"
    done
done

# --- the USB stick ------------------------------------------------------------------
MEDIA_BIND=""
if [ -n "${RB_ANDROID_MEDIA_DIR:-}" ] && [ -d "${RB_ANDROID_MEDIA_DIR}" ]; then
    MEDIA_BIND="-b $RB_ANDROID_MEDIA_DIR:$RB_CHROOT_MEDIA"
    echo "start-android: media $RB_ANDROID_MEDIA_DIR -> $RB_CHROOT_MEDIA"
    if [ ! -d "$RB_ANDROID_MEDIA_DIR/PIONEER" ]; then
        echo "start-android: WARNING: no PIONEER/ folder in $RB_ANDROID_MEDIA_DIR —" >&2
        echo "start-android:          that is not a rekordbox export; rbp will find no tracks." >&2
    fi
fi

# --- the shim environment -----------------------------------------------------------
# Same list as start-rb.sh's SHIM_VARS (rb.conf's RB_<NAME> becomes <NAME>), plus the
# Android bridge's own. Empty means unset for every one of them.
SHIM_VARS="
DFB_PRESENT DFB_ROTATE PAN_PACER_MS FB_DEV FB_LIE_BPP
DFB_PRESENT_AUTO DFB_PRESENT_FIT DFB_PRESENT_SKIP DFB_PRESENT_PX_BUDGET
POINT_KIND POINT_DEV POINT_DEBUG POINT_MIN_DWELL_MS POINT_MOUSE_SPEED
POINT_SWAP_XY POINT_INVERT_X POINT_INVERT_Y POINT_CURSOR POINT_CURSOR_MS
POINT_QUANTIZE_TAP POINT_MENU POINT_MENU_MOUSE POINT_FX_TOUCH POINT_HOTCUE_TOUCH
AUDIO_DEV AUDIO_CHANNELS AUDIO_MAP AUDIO_FMT AUDIO_MONITOR_PAIR
AUDIO_MIRROR_DEV AUDIO_MIRROR_FMT AUDIO_MIRROR_REOPEN_MS AUDIO_MIRROR_BOOST_DB
STARTUP_MUTE_MS STARTUP_FADE_MS SCHED_RT
MIDI_MAP EVDEV_MAP MIDI_IN_MATCH MIDI_OUT_MATCH MIDI_DUMP MIDI_REPLAY
MIDI_REPLAY_SPEED KBD_DEV CTRL_KEEPALIVE
LED_VU LED_VU_SEGMENTS LED_PADS LED_DISABLE PAD_BRIGHT JOG_PPR
JOG_SCALE JOG_REV JOG_IDLE_MS KNOB_SCALE TEMPO_REV MIRROR_GAIN_MID
KNOB_VERBOSE JOG_VERBOSE TEMPO_VERBOSE LED_VERBOSE
SHMSTATE_STRICT
CRASH_LOG
NETALIAS NETALIAS_IFACE NETALIAS_MAC_SCRAPE NETALIAS_LOG
NETALIAS_CONNECT NETALIAS_CONNECT_FILE
"
for v in $SHIM_VARS; do
    eval "val=\${RB_$v:-}"
    export "$v=$val"
done
export ANDROID_BRIDGE=1
export ANDROID_FB_FILE=/tmp/rb_fb.raw
export ANDROID_TOUCH_FIFO=/tmp/rb_touch.fifo
export ANDROID_MIDI_IN=/tmp/rb_midi_in.fifo
export ANDROID_MIDI_OUT=/tmp/rb_midi_out.fifo
export ANDROID_MIDI_NAME="${ANDROID_MIDI_NAME:-DDJ-FLX4}"
export EDB_BIN=/usr/bin

# --- what runs inside the chroot -----------------------------------------------------
# One proot session for both processes: a proot per process is a ptrace tracer per
# process, and each one costs.
INNER="$CH/root/rb-android-inner.sh"
cat > "$INNER" <<INNER_EOF
#!/bin/sh
$RB_LOADER /usr/bin/edb_streamd >>/tmp/edb_streamd.log 2>&1 &
sleep 1
exec env PATH=/bin:/sbin:/usr/bin:/usr/sbin LD_PRELOAD=$RB_LD_PRELOAD $RB_LOADER $RB_PLAYER -a
INNER_EOF
chmod 755 "$INNER"

export PROOT_TMP_DIR="$RB_TMP"
[ -n "${RB_PROOT_LOADER:-}" ] && export PROOT_LOADER="$RB_PROOT_LOADER"
if [ -n "${RB_PROOT_LOADER32:-}" ]; then
    export PROOT_LOADER_32="$RB_PROOT_LOADER32"
else
    echo "start-android: WARNING: RB_PROOT_LOADER32 not set; a 32-bit guest will not start" >&2
fi
# seccomp acceleration crashes proot on a number of Android builds; correctness
# first, the player is not syscall-bound.
export PROOT_NO_SECCOMP=1

echo "start-android: launching rbp (log: $RBP_LOG)"
# shellcheck disable=SC2086
"$RB_PROOT" -0 -r "$CH" --kill-on-exit -w /root \
    -b /proc -b /sys \
    -b /dev/null -b /dev/zero -b /dev/urandom -b /dev/random \
    $MEDIA_BIND \
    /bin/sh /root/rb-android-inner.sh \
    </dev/null >>"$RBP_LOG" 2>&1 &
PROOT_PID=$!
echo "$PROOT_PID" > "$RB_ROOT/rb.pid"

ANNOUNCE_PID=""
cleanup() {
    [ -n "$ANNOUNCE_PID" ] && kill "$ANNOUNCE_PID" 2>/dev/null
    kill "$PROOT_PID" 2>/dev/null
    sleep 1
    kill -9 "$PROOT_PID" 2>/dev/null
    rm -f "$RB_ROOT/rb.pid"
}
trap 'cleanup; exit 0' TERM INT HUP

# --- tell rbp about the stick, once it is listening ----------------------------------
# On the Pi usb-watch.sh waits until rbp holds /tmp/udev_usb1 open and then writes
# "mount <path>" into it. A write to a FIFO blocks until a reader exists, so this
# subshell simply waits for rbp to open it; a mount event sent earlier would be lost.
if [ -n "$MEDIA_BIND" ]; then
    (
        printf '%s' "${RB_ANDROID_MEDIA_LABEL:-RBEXPORT}" > "$T/udev_usb1.label"
        # Give rbp time to finish initialising after it opens the FIFO.
        printf '%s' "mount $RB_CHROOT_MEDIA" > "$T/udev_usb1"
        echo "start-android: stick announced to rbp" >> "$RBP_LOG"
    ) &
    ANNOUNCE_PID=$!
fi

# --- wait --------------------------------------------------------------------------
# Foreground: the app's service owns this process and stops it with a signal.
while kill -0 "$PROOT_PID" 2>/dev/null; do
    sleep 2
done
echo "start-android: rbp exited (see $RBP_LOG)"
[ -n "$ANNOUNCE_PID" ] && kill "$ANNOUNCE_PID" 2>/dev/null
rm -f "$RB_ROOT/rb.pid"
