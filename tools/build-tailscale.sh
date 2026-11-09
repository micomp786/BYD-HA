#!/usr/bin/env bash
# Builds the combined tailscale + tailscaled binary for the head unit (android/arm64) from
# upstream source and places it where Gradle packs it into the APK as libtailscale.so.
# Android extracts files named lib*.so with execute permission, which is why it has that name.
set -euo pipefail
TS_VERSION="${TS_VERSION:-v1.96.4}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libtailscale.so"
WORK="${WORK:-$(mktemp -d)}"

# Same feature set as Overdrive's head-unit build, minus nothing: CLI linked in, desktop/cloud
# integrations left out. The SOCKS5 proxy (outboundproxy) must stay in.
TAGS=ts_include_cli
TAGS+=,ts_omit_aws,ts_omit_kube,ts_omit_cloud,ts_omit_synology
TAGS+=,ts_omit_bird,ts_omit_dbus,ts_omit_networkmanager,ts_omit_resolved
TAGS+=,ts_omit_desktop_sessions,ts_omit_systray,ts_omit_colorable
TAGS+=,ts_omit_completion,ts_omit_completion_scripts,ts_omit_webbrowser
TAGS+=,ts_omit_qrcodes,ts_omit_clientupdate,ts_omit_drive,ts_omit_taildrop
TAGS+=,ts_omit_webclient,ts_omit_tap,ts_omit_tpm,ts_omit_capture
TAGS+=,ts_omit_debugportmapper,ts_omit_debugeventbus,ts_omit_doctor
TAGS+=,ts_omit_hujsonconf,ts_omit_identityfederation,ts_omit_oauthkey
TAGS+=,ts_omit_sdnotify

if [ ! -d "$WORK/tailscale" ]; then
    git clone --depth 1 --branch "$TS_VERSION" https://github.com/tailscale/tailscale.git "$WORK/tailscale"
fi
mkdir -p "$(dirname "$OUT")"
(
    cd "$WORK/tailscale"
    GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
        go build -tags "$TAGS" -trimpath -ldflags "-s -w" -o "$OUT" ./cmd/tailscaled
)
ls -la "$OUT"
