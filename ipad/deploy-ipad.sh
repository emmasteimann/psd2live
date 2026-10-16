#!/bin/bash
# Build Layerloom and put it on your iPad, in one go (like Goose World's tools/deploy-iphone.sh):
#
#   ipad/deploy-ipad.sh              build (Release: fast on the iPad), install, launch
#   ipad/deploy-ipad.sh --debug      a Debug build: quicker to build, slower to use
#   ipad/deploy-ipad.sh --no-build   install and launch the last build
#   ipad/deploy-ipad.sh --no-launch  build and install only
#   ipad/deploy-ipad.sh --device NAME  pick a device by name (default: the first paired iPad)
#
# Needs: the iPad plugged in or on the same Wi-Fi (paired with this Mac in Xcode once), unlocked for the launch,
# Developer Mode on. Your projects on the iPad are kept: installing over the app keeps its files.
#
# Free (personal team) signing expires after 7 days: when Layerloom stops opening on the iPad, run this again.

set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"

TEAM="${LAYERLOOM_TEAM:-6Q6Y6U54M6}"
BUNDLE_ID="com.goosible.layerloom.ipad"
DERIVED="$ROOT/build/device-derived"
LOG_DIR="$ROOT/build/logs"
CONFIG="Release"
BUILD=1
LAUNCH=1
WANT="${LAYERLOOM_DEVICE:-}"
NEXT_IS_DEVICE=0
for arg in "$@"; do
    if [ "$NEXT_IS_DEVICE" = 1 ]; then WANT="$arg"; NEXT_IS_DEVICE=0; continue; fi
    case "$arg" in
        --device) NEXT_IS_DEVICE=1 ;;
        --debug) CONFIG="Debug" ;;
        --no-build) BUILD=0 ;;
        --no-launch) LAUNCH=0 ;;
        -h|--help) sed -n 2,13p "$0"; exit 0 ;;
        *) echo "Unknown option $arg (try --help)"; exit 2 ;;
    esac
done

say() { printf '\033[1;35m▸\033[0m %s\n' "$*"; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- The iPad: a paired iPad first (never the iPhone unless asked for by name), connected ones first. ---
xcrun devicectl list devices --json-output "$TMP/devices.json" >/dev/null 2>&1 || true
read -r DEVICE UDID NAME < <(python3 - "$TMP/devices.json" "$WANT" <<'EOF'
import json, sys
try:
    devices = json.load(open(sys.argv[1]))["result"]["devices"]
except Exception:
    devices = []
want = sys.argv[2].lower()
name = lambda d: d.get("deviceProperties", {}).get("name", "")
kind = lambda d: d.get("hardwareProperties", {}).get("deviceType", "")
paired = [d for d in devices if d.get("hardwareProperties", {}).get("platform") == "iOS"
          and d.get("connectionProperties", {}).get("pairingState") == "paired"]
if want:
    paired = [d for d in paired if want in name(d).lower() or want in kind(d).lower()]
else:
    paired = [d for d in paired if kind(d) == "iPad"]
paired.sort(key=lambda d: d.get("connectionProperties", {}).get("tunnelState") != "connected")
if not paired:
    print("- - -")
else:
    d = paired[0]
    print(d["identifier"], d["hardwareProperties"]["udid"], name(d).replace(" ", "_") or "iPad")
EOF
)
if [ "$DEVICE" = "-" ]; then
    echo "No paired iPad found. Plug it in (or same Wi-Fi), unlock it, tap Trust, and turn on"
    echo "Settings > Privacy & Security > Developer Mode (pair it in Xcode > Devices once). Then run this again."
    exit 1
fi
say "iPad: ${NAME//_/ }"

APP="$DERIVED/Build/Products/$CONFIG-iphoneos/Layerloom.app"
if [ "$BUILD" = 1 ]; then
    mkdir -p "$LOG_DIR"
    XLOG="$LOG_DIR/xcode-ipad-$CONFIG-$(date +%Y%m%d-%H%M%S).log"  # one per run
    if [ "$CONFIG" = "Release" ]; then
        say "Building (Release). The Kotlin part is optimised, which takes a few minutes the first time and keeps the Mac busy;"
        say "later builds reuse most of it. (--debug builds faster but the app runs slower.)"
    else
        say "Building (Debug)…"
    fi
    # Another build of this app already running (a second run of this script, or one started elsewhere): wait for it
    # rather than both fighting over the build folder ("database is locked").
    busy() { pgrep -f "xcodebuild.*-derivedDataPath $DERIVED" >/dev/null; }
    if busy; then
        say "Another Layerloom build is running: waiting for it to finish…"
        for _ in $(seq 1 120); do busy || break; sleep 5; done
        if busy; then echo "The other build is still going after 10 minutes. Try again when it's done."; exit 1; fi
    fi
    rm -rf "$APP"  # never install a stale app
    if ! (cd iosApp && nice xcodebuild -project Layerloom.xcodeproj -scheme Layerloom -configuration "$CONFIG" \
        -destination "id=$UDID" -derivedDataPath "$DERIVED" -allowProvisioningUpdates \
        DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic build >"$XLOG" 2>&1); then
        grep -aE "^e: |error:|errSec|failed with a nonzero|BUILD FAILED|No profiles|provisioning" "$XLOG" | sort -u | tail -20
        if grep -aq "errSecInternalComponent" "$XLOG"; then
            echo "Code signing couldn't use your key: the login keychain is probably locked."
            echo "Run:  security unlock-keychain ~/Library/Keychains/login.keychain-db   then try again."
        fi
        if grep -aqiE "No Account for Team|No profiles for|requires a provisioning profile" "$XLOG"; then
            echo "Xcode isn't signed in for team $TEAM: open Xcode > Settings > Accounts and sign in, then try again."
        fi
        echo "Build failed; full log: $XLOG"
        exit 1
    fi
    say "Build succeeded."
fi

if [ ! -d "$APP" ]; then
    echo "No built app at $APP (run without --no-build, or with --debug if the last build was Debug)."
    exit 1
fi

say "Installing…"
if ! xcrun devicectl device install app --device "$DEVICE" "$APP" >"$TMP/install.log" 2>&1; then
    grep -iE "error|denied|developer mode|untrusted" "$TMP/install.log" | head -5
    if grep -qi "developer mode" "$TMP/install.log"; then
        echo "Turn on Settings > Privacy & Security > Developer Mode on the iPad (it restarts), then run this again."
    fi
    echo "Install failed; details above."
    exit 1
fi

if [ "$LAUNCH" = 1 ]; then
    say "Launching…"
    if ! xcrun devicectl device process launch --device "$DEVICE" --terminate-existing "$BUNDLE_ID" >"$TMP/launch.log" 2>&1; then
        if grep -qiE "untrusted|not been trusted|profile" "$TMP/launch.log"; then
            echo "Installed. The first time, the iPad has to trust your developer profile:"
            echo "Settings > General > VPN & Device Management > your Apple ID > Trust. Then open Layerloom."
        else
            echo "Installed, but couldn't launch it (is the iPad locked?). Open Layerloom on the iPad."
        fi
        exit 0
    fi
fi
say "Done."
