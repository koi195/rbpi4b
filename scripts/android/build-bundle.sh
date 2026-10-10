#!/bin/bash
# build-bundle.sh — turn the Pi chroot tarball into the bundle the Android app imports.
#
#   work/rbpi4b-pi4.tgz  (scripts/build-chroot.sh)
#        |
#        |  swap usr/lib/fbshim.so for fbshim-android.so  (framebuffer = shared file)
#        |  add start-android.sh + asound.conf             (launcher, audio -> FIFO)
#        v
#   work/rbpi4b-android.tgz   <- pick this file in the app's "Import bundle" button
#
# Nothing here touches the Pi artifacts: the Pi tarball and the Pi's fbshim.so stay
# exactly as they were.
#
# Inputs (environment, all optional):
#   PI_TGZ   the Pi tarball                  (= work/rbpi4b-pi4.tgz)
#   SHIMS    directory holding the built .so (= scripts/shims; built with
#            `make android` if fbshim-android.so is missing)
#   OUT      output directory                (= work)
#
# The bundle holds the player's own chroot, which you extracted yourself (see
# docs/04-firmware-assets.md). This script adds nothing proprietary and fetches
# nothing.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PI_TGZ="${PI_TGZ:-$REPO/work/rbpi4b-pi4.tgz}"
SHIMS="${SHIMS:-$REPO/scripts/shims}"
OUT="${OUT:-$REPO/work}"

[ -f "$PI_TGZ" ] || {
    echo "build-bundle: $PI_TGZ not found — run scripts/build-chroot.sh first" >&2
    exit 1
}

if [ ! -f "$SHIMS/fbshim-android.so" ]; then
    echo "build-bundle: building the Android shims (make android)"
    make -C "$SHIMS" android
fi
for f in fbshim-android.so knobshim.so audioshim.so; do
    [ -f "$SHIMS/$f" ] || { echo "build-bundle: $SHIMS/$f missing" >&2; exit 1; }
done

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/rbandroid.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

echo "build-bundle: unpacking $PI_TGZ"
tar -xzf "$PI_TGZ" -C "$STAGE" --numeric-owner

CH="$STAGE/rbx3-run"
[ -d "$CH" ] || { echo "build-bundle: no rbx3-run/ in $PI_TGZ" >&2; exit 1; }
[ -f "$STAGE/rb.conf" ] || { echo "build-bundle: no rb.conf in $PI_TGZ" >&2; exit 1; }

# fbshim: the Android variant replaces the Pi one under the same name, because
# rb.conf's RB_LD_PRELOAD names /usr/lib/fbshim.so and the launcher does not edit it.
cp "$SHIMS/fbshim-android.so" "$CH/usr/lib/fbshim.so"
cp "$SHIMS/fbshim-android.so" "$CH/root/pdj/fbshim.so"
cp "$SHIMS/knobshim.so"       "$CH/usr/lib/knobshim.so"
cp "$SHIMS/audioshim.so"      "$CH/usr/lib/audioshim.so"

# Launcher + the chroot's ALSA config sit next to rb.conf (RB_ROOT).
install -m 755 "$HERE/start-android.sh" "$STAGE/start-android.sh"
install -m 644 "$HERE/asound.conf"      "$STAGE/asound.conf"

# The Pi's boot-time files are meaningless here; keep the bundle lean.
rm -rf "$STAGE/device" 2>/dev/null || true

mkdir -p "$OUT"
DEST="$OUT/rbpi4b-android.tgz"
tar -czf "$DEST" -C "$STAGE" --numeric-owner --owner=0 --group=0 .
echo "build-bundle: wrote $DEST ($(du -h "$DEST" | cut -f1))"
echo "build-bundle: copy it to the tablet and use 'Import bundle' in the app"
