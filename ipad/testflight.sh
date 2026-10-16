#!/bin/bash
# Build Layerloom for the App Store and upload it to TestFlight, in one go:
#
#   ipad/testflight.sh --team TEAMID        first time: your paid team's ID (developer.apple.com > Membership details);
#                                            it is remembered in ipad/.testflight-team for next time
#   ipad/testflight.sh                       later uploads
#   ipad/testflight.sh --no-upload           build and export only (the .ipa lands in ipad/build/testflight/export)
#
# Needs, once: Xcode > Settings > Accounts signed in with the paid developer account, and the app created in App Store
# Connect (My Apps > + > New App, bundle ID com.goosible.layerloom). Signing uses that Xcode account - no API key
# or password is stored anywhere. Each upload gets a fresh build number (the date and time), so uploads never clash.
# After an upload, App Store Connect processes it (~10-30 min); then it appears in TestFlight on the iPad.

set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
TEAM_FILE="$ROOT/.testflight-team"
TEAM="${LAYERLOOM_TEAM:-}"
UPLOAD=1
NEXT_IS_TEAM=0
for arg in "$@"; do
    if [ "$NEXT_IS_TEAM" = 1 ]; then TEAM="$arg"; NEXT_IS_TEAM=0; continue; fi
    case "$arg" in
        --team) NEXT_IS_TEAM=1 ;;
        --no-upload) UPLOAD=0 ;;
        -h|--help) sed -n 2,12p "$0"; exit 0 ;;
        *) echo "Unknown option $arg (try --help)"; exit 2 ;;
    esac
done
[ -z "$TEAM" ] && [ -f "$TEAM_FILE" ] && TEAM="$(cat "$TEAM_FILE")"
if [ -z "$TEAM" ]; then
    echo "Which team? Run:  ipad/testflight.sh --team YOURTEAMID"
    echo "(developer.apple.com > Account > Membership details > Team ID - ten letters and digits)"
    exit 1
fi
echo "$TEAM" > "$TEAM_FILE"

say() { printf '\033[1;35m▸\033[0m %s\n' "$*"; }
OUT="$ROOT/build/testflight"
ARCHIVE="$OUT/Layerloom.xcarchive"
LOG_DIR="$ROOT/build/logs"; mkdir -p "$LOG_DIR" "$OUT"
LOG="$LOG_DIR/testflight-$(date +%Y%m%d-%H%M%S).log"
BUILD_NUMBER="$(date +%Y%m%d%H%M)"

[ -f iosApp/models/SAM2_1TinyMaskDecoderFLOAT16.mlpackage/Manifest.json ] || { say "Downloading the smart mask model (once, ~76 MB)…"; scripts/fetch-sam.sh; }
if [ ! -d iosApp/Layerloom.xcodeproj ] || [ iosApp/project.yml -nt iosApp/Layerloom.xcodeproj/project.pbxproj ]; then
    if command -v xcodegen >/dev/null; then say "Regenerating the Xcode project…"; (cd iosApp && xcodegen generate -q)
    else echo "iosApp/project.yml changed but XcodeGen isn't installed (brew install xcodegen)."; exit 1; fi
fi

say "Building for the App Store (Release, build $BUILD_NUMBER). This takes a few minutes and keeps the Mac busy."
rm -rf "$ARCHIVE" "$OUT/export"
if ! (cd iosApp && nice xcodebuild -project Layerloom.xcodeproj -scheme Layerloom -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" -allowProvisioningUpdates \
    DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic CURRENT_PROJECT_VERSION="$BUILD_NUMBER" archive >"$LOG" 2>&1); then
    grep -aE "^e: |error:|BUILD FAILED|No Account|No profiles|provisioning" "$LOG" | sort -u | tail -20
    echo "The build failed; full log: $LOG"
    exit 1
fi

DEST=$([ "$UPLOAD" = 1 ] && echo upload || echo export)
cat > "$OUT/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>$DEST</string>
  <key>teamID</key><string>$TEAM</string>
  <key>signingStyle</key><string>automatic</string>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
EOF

if [ "$UPLOAD" = 1 ]; then say "Uploading to App Store Connect…"; else say "Exporting the .ipa…"; fi
if ! xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportPath "$OUT/export" \
    -exportOptionsPlist "$OUT/ExportOptions.plist" -allowProvisioningUpdates >>"$LOG" 2>&1; then
    grep -aiE "error|failed|not found|No suitable application records" "$LOG" | sort -u | tail -15
    if grep -aqi "No suitable application records" "$LOG"; then
        echo "App Store Connect has no app with this bundle ID yet: My Apps > + > New App (bundle ID com.goosible.layerloom)."
    fi
    echo "The upload failed; full log: $LOG"
    exit 1
fi

if [ "$UPLOAD" = 1 ]; then
    say "Uploaded build $BUILD_NUMBER. App Store Connect processes it (~10-30 min), then it shows in TestFlight."
else
    say "Exported to $OUT/export"
fi
