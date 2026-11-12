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
ARCHIVE_DIR="build/releases_archive"
mkdir -p build "$ARCHIVE_DIR"

# Preserve any previously built versioned APKs in build/releases_archive before cleaning build/
shopt -s nullglob
for prev_apk in build/SoundTracker-v*.apk; do
  base_name="$(basename "$prev_apk")"
  if [[ "$base_name" != "SoundTracker-${TAG}.apk" && ! -f "$ARCHIVE_DIR/$base_name" ]]; then
    cp -f "$prev_apk" "$ARCHIVE_DIR/$base_name"
  fi
done
shopt -u nullglob

# Ensure up to the 2 most recent published GitHub Release APKs are cached locally for delta diffing
RECENT_TAGS="$(gh release list -R "$REPO" --limit 3 --json tagName -q '.[].tagName' 2>/dev/null || true)"
FETCHED_COUNT=0
for rtag in $RECENT_TAGS; do
  [[ -z "$rtag" || "$rtag" == "$TAG" ]] && continue
  FETCHED_COUNT=$((FETCHED_COUNT + 1))
  [[ $FETCHED_COUNT -gt 2 ]] && break
  cached_apk="$ARCHIVE_DIR/SoundTracker-${rtag}.apk"
  if [[ ! -f "$cached_apk" ]]; then
    echo "==> Caching previous release APK (${rtag}) for incremental delta generation..."
    gh release download "$rtag" -R "$REPO" -p "SoundTracker-${rtag}.apk" -D "$ARCHIVE_DIR" --clobber 2>/dev/null || true
  fi
done

if [[ "$SKIP_BUILD" == "false" ]]; then
  echo "==> Building Release APK for SoundTracker ${TAG} (versionCode=${VERSION_CODE})..."
  ./gradlew assembleRelease
fi

if [[ ! -f "$APK_RELEASE_OUT" ]]; then
  echo "Error: Release APK not found at $APK_RELEASE_OUT" >&2
  exit 1
fi

rm -f build/SoundTracker-v*.apk build/SoundTracker-*-to-*.stpatch build/patch-meta-*.json build/update-manifest.json
VERSIONED_APK="build/SoundTracker-${TAG}.apk"
cp -f "$APK_RELEASE_OUT" "build/SoundTracker.apk"
cp -f "$APK_RELEASE_OUT" "$VERSIONED_APK"
cp -f "$APK_RELEASE_OUT" "$ARCHIVE_DIR/SoundTracker-${TAG}.apk"

SHA256_HEX="$(shasum -a 256 "$VERSIONED_APK" | awk '{print $1}')"
APK_BYTES="$(wc -c < "$VERSIONED_APK" | tr -d ' ')"
APK_SIZE_H="$(python3 -c "print(f'{int($APK_BYTES)/(1024*1024):.1f} MiB')")"

# Generate STPATCH1 incremental binary delta patches from cached previous releases
chmod +x scripts/make_apk_patch.py
PATCH_FILES=()
PATCH_META_FILES=()
DELTA_SUMMARY_LINES=""

shopt -s nullglob
for base_apk in "$ARCHIVE_DIR"/SoundTracker-v*.apk; do
  base_name="$(basename "$base_apk")"
  prev_tag="${base_name#SoundTracker-}"
  prev_tag="${prev_tag%.apk}"
  [[ "$prev_tag" == "$TAG" ]] && continue

  patch_name="SoundTracker-${prev_tag}-to-${TAG}.stpatch"
  patch_out="build/${patch_name}"
  meta_out="build/patch-meta-${prev_tag}.json"

  echo "==> Generating incremental patch: ${prev_tag} -> ${TAG}..."
  if python3 scripts/make_apk_patch.py "$base_apk" "$VERSIONED_APK" "$patch_out" --json-out "$meta_out"; then
    PATCH_FILES+=("$patch_out")
    PATCH_META_FILES+=("${prev_tag}:${patch_name}:${meta_out}")
    patch_line="$(python3 - "$meta_out" "$prev_tag" "$TAG" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    m = json.load(f)
p_mb = m["patchSizeBytes"] / (1024.0 * 1024.0)
saved_pct = (1.0 - m["patchSizeBytes"] / max(1, m["targetSizeBytes"])) * 100.0
print(f"- 增量差分包（`{sys.argv[2]} → {sys.argv[3]}`）：`{p_mb:.2f} MiB`（节省 `{saved_pct:.1f}%` 下载体积）")
PY
)"
    if [[ -z "$DELTA_SUMMARY_LINES" ]]; then
      DELTA_SUMMARY_LINES="$patch_line"
    else
      DELTA_SUMMARY_LINES="${DELTA_SUMMARY_LINES}
${patch_line}"
    fi
  fi
done
shopt -u nullglob

# Prune local archive to keep only the 3 most recent APKs
ls -1t "$ARCHIVE_DIR"/SoundTracker-v*.apk 2>/dev/null | tail -n +4 | xargs rm -f 2>/dev/null || true

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

FOOTER_BLOCK="- 版本号：\`${TAG}\` (\`versionCode ${VERSION_CODE}\`)"
if [[ -n "$DELTA_SUMMARY_LINES" ]]; then
  FOOTER_BLOCK="${FOOTER_BLOCK}
${DELTA_SUMMARY_LINES}"
fi
FOOTER_BLOCK="${FOOTER_BLOCK}
- 完整安装包大小：\`${APK_SIZE_H}\`
- SHA256: \`${SHA256_HEX}\`"

FINAL_NOTES="${CUSTOM_NOTES}

---
${FOOTER_BLOCK}"

# Generate update-manifest.json for client-side incremental matching & API-rate-limit fallback
MANIFEST_OUT="build/update-manifest.json"
PUBLISHED_ISO="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
python3 - "$MANIFEST_OUT" "$REPO" "$TAG" "$VERSION_NAME" "$VERSION_CODE" "$APK_BYTES" "$SHA256_HEX" "$PUBLISHED_ISO" "$FINAL_NOTES" "${PATCH_META_FILES[@]+"${PATCH_META_FILES[@]}"}" <<'PY'
import json
import sys

manifest_out = sys.argv[1]
repo = sys.argv[2]
tag = sys.argv[3]
version_name = sys.argv[4]
version_code = int(sys.argv[5])
apk_bytes = int(sys.argv[6])
apk_sha = sys.argv[7]
published_iso = sys.argv[8]
final_notes = sys.argv[9]
patch_specs = sys.argv[10:]

apk_name = f"SoundTracker-{tag}.apk"
base_dl = f"https://github.com/{repo}/releases/download/{tag}"

patches = []
for spec in patch_specs:
    if not spec:
        continue
    prev_tag, patch_name, meta_path = spec.split(":", 2)
    with open(meta_path, "r", encoding="utf-8") as f:
        m = json.load(f)
    from_ver = prev_tag.lstrip("vV")
    patches.append({
        "fromTag": prev_tag,
        "fromVersion": from_ver,
        "sourceSizeBytes": m["sourceSizeBytes"],
        "sourceSha256": m["sourceSha256"],
        "targetSizeBytes": m["targetSizeBytes"],
        "targetSha256": m["targetSha256"],
        "name": patch_name,
        "url": f"{base_dl}/{patch_name}",
        "patchSizeBytes": m["patchSizeBytes"],
        "patchSha256": m["patchSha256"],
    })

manifest = {
    "schema": 1,
    "tagName": tag,
    "versionName": version_name,
    "versionCode": version_code,
    "releaseTitle": f"SoundTracker {tag}",
    "publishedAt": published_iso,
    "releaseNotes": final_notes,
    "apk": {
        "name": apk_name,
        "url": f"{base_dl}/{apk_name}",
        "sizeBytes": apk_bytes,
        "sha256": apk_sha,
    },
    "patches": patches,
}

with open(manifest_out, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
PY

rm -f build/patch-meta-*.json

UPLOAD_ASSETS=("$VERSIONED_APK" "$MANIFEST_OUT")
if [[ ${#PATCH_FILES[@]} -gt 0 ]]; then
  UPLOAD_ASSETS+=("${PATCH_FILES[@]}")
fi

echo "==> Publishing ${TAG} (${APK_SIZE_H}, ${#PATCH_FILES[@]} delta patch(es)) to GitHub Releases (${REPO})..."
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "${UPLOAD_ASSETS[@]}" -R "$REPO" --clobber
  gh release edit "$TAG" -R "$REPO" --title "SoundTracker ${TAG}" --notes "$FINAL_NOTES"
else
  gh release create "$TAG" "${UPLOAD_ASSETS[@]}" \
    -R "$REPO" \
    --target main \
    --title "SoundTracker ${TAG}" \
    --notes "$FINAL_NOTES"
fi

echo "==> Done! Release live at: https://github.com/${REPO}/releases/tag/${TAG}"
