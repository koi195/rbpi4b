#!/bin/bash
# fetch-proot.sh — put an arm64 proot where the Android build packs native libraries.
#
#   android/app/src/main/jniLibs/arm64-v8a/
#       libproot.so            the proot executable
#       libproot-loader.so     its 64-bit loader
#       libproot-loader32.so   its 32-bit loader  (rbp is ARM32: required)
#       libtalloc.so           the one shared library proot links against
#
# Android only lets an app execute files that live in its native-library directory,
# and only files named lib*.so are installed there, so the executable and its
# loaders are shipped disguised as libraries. The app runs proot by path (RB_PROOT)
# and points it at the loaders with PROOT_LOADER / PROOT_LOADER_32; RbService links
# libtalloc.so as libtalloc.so.2 at run time, which is the name proot asks for.
#
# Source: the Termux project's packages (proot, libtalloc; GPL-2.0+ / LGPL). Nothing
# is built here and nothing proprietary is involved.
#
# NOT VERIFIED on a device. Things this script assumes about Termux's packaging:
#   - the package index is at $TERMUX_REPO/dists/stable/main/binary-aarch64/Packages
#   - proot's .deb carries bin/proot and libexec/proot/loader + loader32
#   - libtalloc's .deb carries lib/libtalloc.so.2*
# If any of that has moved, it stops with a message; set PROOT_DEB_URL /
# TALLOC_DEB_URL to point at the .debs yourself.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
DEST="${DEST:-$REPO/android/app/src/main/jniLibs/arm64-v8a}"
BASE="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/proot.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

INDEX=""
deb_url() {   # $1 = package name -> prints the .deb URL
    if [ -z "$INDEX" ]; then
        echo "fetch-proot: reading the package index" >&2
        curl -fsSL "$BASE/dists/stable/main/binary-aarch64/Packages" -o "$WORK/Packages"
        INDEX=1
    fi
    local rel
    rel="$(awk -v p="$1" '
        $0 == "Package: " p {f=1; next}
        f && /^Filename:/ {print $2; exit}
        f && /^$/ {f=0}' "$WORK/Packages")"
    [ -n "$rel" ] || { echo "fetch-proot: package '$1' not in the index" >&2; return 1; }
    echo "$BASE/$rel"
}

unpack() {    # $1 = url, $2 = dir
    mkdir -p "$2"
    echo "fetch-proot: $1" >&2
    curl -fsSL "$1" -o "$2/pkg.deb"
    ( cd "$2" && ar x pkg.deb && tar -xf data.tar.* )
}

PROOT_DEB_URL="${PROOT_DEB_URL:-$(deb_url proot)}"
TALLOC_DEB_URL="${TALLOC_DEB_URL:-$(deb_url libtalloc)}"
unpack "$PROOT_DEB_URL"  "$WORK/proot"
unpack "$TALLOC_DEB_URL" "$WORK/talloc"

BIN="$(find "$WORK/proot"  -type f -name proot    -path '*/bin/*'          | head -1)"
LDR="$(find "$WORK/proot"  -type f -name loader   -path '*proot*'          | head -1)"
LD32="$(find "$WORK/proot" -type f -name loader32 -path '*proot*'          | head -1)"
TAL="$(find "$WORK/talloc" \( -type f -o -type l \) -name 'libtalloc.so*' | head -1)"

[ -n "$BIN" ]  || { echo "fetch-proot: no bin/proot in the package" >&2; exit 1; }
[ -n "$LDR" ]  || { echo "fetch-proot: no proot loader in the package" >&2; exit 1; }
[ -n "$LD32" ] || { echo "fetch-proot: no proot loader32 in the package — a 32-bit guest cannot run without it" >&2; exit 1; }
[ -n "$TAL" ]  || { echo "fetch-proot: no libtalloc in the package" >&2; exit 1; }

mkdir -p "$DEST"
install -m 755 "$BIN"          "$DEST/libproot.so"
install -m 755 "$LDR"          "$DEST/libproot-loader.so"
install -m 755 "$LD32"         "$DEST/libproot-loader32.so"
install -m 755 "$(readlink -f "$TAL")" "$DEST/libtalloc.so"
echo "fetch-proot: installed into $DEST"
ls -l "$DEST"
