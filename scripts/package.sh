#!/bin/bash
# Unsigned Release packages for sideloading (AltStore / Sideloadly re-sign on install).
#   scripts/package.sh [outdir]   → STV-<ver>-unsigned.ipa  +  STV-<ver>-simulator.app.zip
# Version comes from project.yml.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-$PWD/dist}"
VER=$(awk -F'"' '/MARKETING_VERSION/{print $2; exit}' project.yml)
BUILD=$(awk -F'"' '/CURRENT_PROJECT_VERSION/{print $2; exit}' project.yml)
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
LOG_DIR="${STV_BUILD_LOG_DIR:-$OUT/logs}"
mkdir -p "$LOG_DIR"
xcodegen generate >/dev/null
common=(-project STV.xcodeproj -scheme STV -configuration Release CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="")

xcodebuild "${common[@]}" -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath DerivedDataRelease build 2>&1 | tee "$LOG_DIR/ios.log"
xcodebuild "${common[@]}" -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath DerivedDataRelease build 2>&1 | tee "$LOG_DIR/simulator.log"
if [[ "${STV_RUN_TESTS:-0}" == "1" ]]; then
  SIM_ID=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(x["udid"] for devices in d["devices"].values() for x in devices if x["name"].startswith("iPhone")))')
  xcodebuild -project STV.xcodeproj -scheme STV -configuration Debug \
    -destination "platform=iOS Simulator,id=$SIM_ID" -derivedDataPath DerivedDataRelease \
    CODE_SIGNING_ALLOWED=NO -parallel-testing-enabled NO \
    -only-testing:STVTests/LegadoTests -only-testing:STVTests/PluginTests/testCMSCategoryRequestsKeepFullCategoryList \
    -only-testing:STVTests/PluginTests/testMacCMSParsing -only-testing:STVTests/CENCStreamTests \
    test 2>&1 | tee "$LOG_DIR/tests.log"
fi

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/Payload"
cp -R DerivedDataRelease/Build/Products/Release-iphoneos/STV.app "$STAGE/Payload/"
NAME="${STV_PKG_NAME:-${VER}b${BUILD}}"
IPA="$OUT/STV-$NAME-unsigned.ipa"
rm -f "$IPA"; (cd "$STAGE" && zip -qry "$IPA" Payload)
SIMZIP="$OUT/STV-$NAME-simulator.app.zip"
rm -f "$SIMZIP"; (cd DerivedDataRelease/Build/Products/Release-iphonesimulator && zip -qry "$SIMZIP" STV.app)
rm -rf "$STAGE"
echo "version $VER ($BUILD)"
ls -l "$IPA" "$SIMZIP"
shasum -a 256 "$IPA" "$SIMZIP"
