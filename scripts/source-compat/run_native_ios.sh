#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${SIMULATOR_ID:?Set SIMULATOR_ID to your own booted iOS simulator UUID}"
ROOT="$(pwd -P)"
mkdir -p "$ROOT/artifacts/source-compat"
export TEST_RUNNER_STV_COMPAT_COLLECTION="$ROOT/android/app/src/androidTest/assets/source-compat/filter-sources.json"
export TEST_RUNNER_STV_COMPAT_BOOK="$ROOT/examples/source-compat/classic-source.json"
export TEST_RUNNER_STV_COMPAT_BUNDLE="$ROOT/examples/source-compat/legal-animation.idjplugin"
export TEST_RUNNER_STV_COMPAT_AI="${STV_COMPAT_AI:-0}"
export TEST_RUNNER_STV_SHOT_DIR="$ROOT/artifacts/source-compat"
packages=()
if [[ -n "${SOURCE_PACKAGES:-}" ]]; then packages=(-clonedSourcePackagesDirPath "$SOURCE_PACKAGES"); fi
xcodebuild -project STV.xcodeproj -scheme STV -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
 -derivedDataPath "${DERIVED_DATA:-/tmp/idj-source-compat-dd}" \
 "${packages[@]}" test \
 -only-testing:STVTests/PluginTests/testAIMirrorRequiresCatalogNotLandingPage \
 -only-testing:STVTests/PluginTests/testOptInHuangguoAIReadOnlyPipeline \
 -only-testing:STVTests/PluginTests/testOptInM3UBundleThroughNativeRuntime \
 -only-testing:STVTests/PluginTests/testSubscriptionRejectsForeignWithoutClobberingCache \
 -only-testing:STVTests/PluginTests/testSubscriptionRefreshPersistsAndRejectsForeignUpdate \
 -only-testing:STVTests/RealRankRegressionTests/testMultilinePostBookURLPreservesIdentity \
 -only-testing:STVTests/RealRankRegressionTests/testDependentFiltersRegenerateUrlsAndStayScoped \
 -only-testing:STVTests/RealRankRegressionTests/testOptInTwoRealSourcesFilterRequests \
 -only-testing:STVTests/RealRankRegressionTests/testOptInPublicDomainClassicFlow \
 -only-testing:STVUITests/NovelUITests/testOptInPublicDomainClassicNativeReader \
 -only-testing:STVUITests/NovelUITests/testOptInTwoRealSourceFilterControls \
 -only-testing:STVUITests/PluginUITests/testOptInReviewedM3UNativePlayback CODE_SIGNING_ALLOWED=NO
