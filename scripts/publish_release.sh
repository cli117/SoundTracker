#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -d "$ROOT_DIR/.tools/jdk" && -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$ROOT_DIR/.tools/jdk"
fi
if [[ -d "$HOME/Library/Android/sdk" && -z "${ANDROID_HOME:-}" ]]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi

SKIP_BUILD=false
CUSTOM_NOTES=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    *)
      CUSTOM_NOTES="$1"
      shift
      ;;
  esac
done

VERSION_NAME="$(grep -E 'versionName\s*=' app/build.gradle.kts | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')"
VERSION_CODE="$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -n 1 | sed -E 's/[^0-9]*([0-9]+).*/\1/')"

if [[ -z "$VERSION_NAME" ]]; then
  echo "Error: Could not parse versionName from app/build.gradle.kts" >&2
  exit 1
fi

TAG="v${VERSION_NAME}"
REPO="cli117/SoundTracker"
APK_RELEASE_OUT="app/build/outputs/apk/release/app-release.apk"
mkdir -p build

if [[ "$SKIP_BUILD" == "false" ]]; then
  echo "==> Building Release APK for SoundTracker ${TAG} (versionCode=${VERSION_CODE})..."
  ./gradlew assembleRelease
fi

if [[ ! -f "$APK_RELEASE_OUT" ]]; then
  echo "Error: Release APK not found at $APK_RELEASE_OUT" >&2
  exit 1
fi

rm -f build/SoundTracker-v*.apk
VERSIONED_APK="build/SoundTracker-${TAG}.apk"
cp -f "$APK_RELEASE_OUT" "build/SoundTracker.apk"
cp -f "$APK_RELEASE_OUT" "$VERSIONED_APK"

SHA256_HEX="$(shasum -a 256 "$VERSIONED_APK" | awk '{print $1}')"
APK_SIZE_H="$(ls -lh "$VERSIONED_APK" | awk '{print $5}')"

if [[ -z "$CUSTOM_NOTES" ]]; then
  PREV_TAG="$(gh release list -R "$REPO" --limit 1 --json tagName -q '.[0].tagName' 2>/dev/null || true)"
  if [[ -n "$PREV_TAG" ]] && git rev-parse "$PREV_TAG" >/dev/null 2>&1; then
    COMMIT_LIST="$(git log "${PREV_TAG}..HEAD" --pretty=format:'- %s (%h)' | head -n 15)"
  else
    COMMIT_LIST="$(git log -n 6 --pretty=format:'- %s (%h)')"
  fi
  CUSTOM_NOTES="### 更新内容
${COMMIT_LIST}"
fi

FINAL_NOTES="${CUSTOM_NOTES}

---
- 版本号：\`${TAG}\` (\`versionCode ${VERSION_CODE}\`)
- 安装包大小：\`${APK_SIZE_H}\`
- SHA256: \`${SHA256_HEX}\`"

echo "==> Publishing ${TAG} (${APK_SIZE_H}) to GitHub Releases (${REPO})..."
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "$VERSIONED_APK" -R "$REPO" --clobber
  gh release edit "$TAG" -R "$REPO" --title "SoundTracker ${TAG}" --notes "$FINAL_NOTES"
else
  gh release create "$TAG" "$VERSIONED_APK" \
    -R "$REPO" \
    --target main \
    --title "SoundTracker ${TAG}" \
    --notes "$FINAL_NOTES"
fi

echo "==> Done! Release live at: https://github.com/${REPO}/releases/tag/${TAG}"
