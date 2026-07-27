#!/usr/bin/env bash
#
# Prepare Android sandbox assets:
#   1. Download Alpine Linux aarch64 minirootfs
#   2. Download PRoot aarch64 static binary from Termux packages
#   3. Place both into src/android/app/src/main/assets/
#
# Downloads are cached in $OPENMINIS_DL_CACHE (default ~/.cache/openminis) so
# CI / repeated local runs don't re-hit the network every time. The CI workflow
# persists that directory with actions/cache, eliminating flaky external
# downloads on warm runs.
#
# Usage: ./scripts/prepare_android_sandbox.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"

# Download cache: keep tarballs/debs here across runs (CI actions/cache restores it)
CACHE_DIR="${OPENMINIS_DL_CACHE:-$HOME/.cache/openminis}"
mkdir -p "$CACHE_DIR"

ALPINE_VERSION="3.21"
ALPINE_RELEASE="3.21.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"

# Termux proot package — aarch64 static binary
PROOT_VERSION="5.1.107-70"
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${PROOT_VERSION}_aarch64.deb"

# download_cached <url> <cache-filename> [verify-command]
#   Downloads to $CACHE_DIR if missing, runs optional verify command on the
#   cached file (must exit non-zero on corruption), echoes the cached PATH.
#   On verification failure it re-downloads once before giving up.
#   NOTE: status messages go to stderr and verify output is discarded, so the
#   only thing printed to stdout is the path (safe for command substitution).
download_cached() {
  local url="$1" name="$2" verify="${3:-}"
  local cached="$CACHE_DIR/$name"
  if [ -f "$cached" ] && { [ -z "$verify" ] || eval "$verify \"$cached\"" >/dev/null 2>&1; }; then
    echo "✓ cached download present: $name" >&2
  else
    echo "Downloading $url ..." >&2
    curl -fSL -o "$cached" "$url"
    if [ -n "$verify" ] && ! eval "$verify \"$cached\"" >/dev/null 2>&1; then
      echo "Downloaded file failed verification, retrying once..." >&2
      rm -f "$cached"
      curl -fSL -o "$cached" "$url"
      if ! eval "$verify \"$cached\"" >/dev/null 2>&1; then
        echo "Error: $name still fails verification after retry" >&2
        exit 1
      fi
    fi
  fi
  echo "$cached"
}

mkdir -p "$ASSETS_DIR"

ROOTFS_FILE="$ASSETS_DIR/alpine-minirootfs.tar.gz"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"

# --- Alpine rootfs ---
if [ -f "$ROOTFS_FILE" ]; then
    echo "✓ Alpine rootfs already exists: $ROOTFS_FILE"
else
    cached_rootfs=$(download_cached "$ALPINE_URL" "alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz" "tar -tzf")
    cp "$cached_rootfs" "$ROOTFS_FILE"
    echo "✓ Placed rootfs: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
fi

# --- PRoot binary ---
if [ -f "$PROOT_FILE" ]; then
    echo "✓ PRoot binary already exists: $PROOT_FILE"
else
    cached_deb=$(download_cached "$PROOT_DEB_URL" "proot_${PROOT_VERSION}_aarch64.deb" "ar t")

    TMPDIR="$(mktemp -d)"
    trap 'rm -rf "$TMPDIR"' EXIT

    cp "$cached_deb" "$TMPDIR/proot.deb"
    cd "$TMPDIR"
    ar x "$TMPDIR/proot.deb"

    # Extract data archive
    if [ -f "data.tar.xz" ]; then
        tar xf data.tar.xz
    elif [ -f "data.tar.gz" ]; then
        tar xzf data.tar.gz
    elif [ -f "data.tar.zst" ]; then
        zstd -d data.tar.zst -o data.tar
        tar xf data.tar
    else
        echo "Error: Could not find data archive in .deb"
        ls -la "$TMPDIR"
        exit 1
    fi

    # Find the proot binary
    PROOT_BIN=$(find "$TMPDIR" -name "proot" -type f | head -1)
    if [ -z "$PROOT_BIN" ]; then
        echo "Error: Could not find proot binary in extracted .deb"
        find "$TMPDIR" -type f
        exit 1
    fi

    cp "$PROOT_BIN" "$PROOT_FILE"
    chmod +x "$PROOT_FILE"
    cd "$PROJECT_ROOT"

    echo "✓ Extracted PRoot binary: $PROOT_FILE ($(du -h "$PROOT_FILE" | cut -f1))"
fi

echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
