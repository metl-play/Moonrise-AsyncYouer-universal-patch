#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./patch_moonrise.sh /path/to/Moonrise-*.jar [/path/to/AsyncYouer-*-server.jar]
#
# Optional environment overrides:
#   NEOFORGE_JAR=/path/to/neoforge-*-universal.jar
#   ASYNC_JAR=/path/to/AsyncYouer-*-server.jar
#   PATCH_SCOPE=all|embedded|lib|auto
#   SERVER_DIR=/path/to/server/root (defaults to dirname(ASYNC_JAR))

PATCH_JAR="${1:-}"
ASYNC_JAR_ARG="${2:-}"
NEOFORGE_JAR="${NEOFORGE_JAR:-/mnt/4tbn/01-MC-Server/create-ive/libraries/net/neoforged/neoforge/21.1.218/neoforge-21.1.218-universal.jar}"
ASYNC_JAR="${ASYNC_JAR:-/mnt/4tbn/01-MC-Server/create-ive/AsyncYouer-1.21.1-4a465566-server.jar}"
EMBEDDED_PATH="data/neoforge-21.1.218-universal.jar"
PATCH_SCOPE="${PATCH_SCOPE:-auto}"
if [ -n "$ASYNC_JAR_ARG" ]; then
  ASYNC_JAR="$ASYNC_JAR_ARG"
fi
SERVER_DIR="${SERVER_DIR:-}"
EXCLUDE_ENTRIES=(
  "ca/spottedleaf/moonrise/neoforge/MoonriseNeoForge.class"
  "ca/spottedleaf/moonrise/patches/chunk_system/io/RegionFileIOThread$CancellableRead.class"
  "ca/spottedleaf/moonrise/patches/chunk_system/io/RegionFileIOThread$CancellableReads.class"
  "ca/spottedleaf/moonrise/patches/chunk_system/io/RegionFileIOThread$ChunkDataTask.class"
  "ca/spottedleaf/moonrise/patches/chunk_system/io/RegionFileIOThread$ImmediateCallbackCompletion.class"
  "ca/spottedleaf/moonrise/patches/chunk_system/io/RegionFileIOThread$InProgressRead.class"
  "ca/spottedleaf/moonrise/patches/chunk_system/io/RegionFileIOThread$RegionFileData.class"
)
SERVICE_ENTRIES=(
  "META-INF/services/ca.spottedleaf.moonrise.common.PlatformHooks"
)

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required tool: $1"
    exit 1
  }
}

require_file() {
  [ -f "$1" ] || {
    echo "Missing file: $1"
    exit 1
  }
}

if [ -z "$PATCH_JAR" ]; then
  echo "Usage: $0 /path/to/Moonrise-*.jar"
  exit 1
fi

for cmd in jar rg sort comm mktemp sha256sum zip; do
  need_cmd "$cmd"
done

require_file "$PATCH_JAR"

case "$PATCH_SCOPE" in
  all|embedded|lib|auto) ;;
  *)
    echo "Invalid PATCH_SCOPE: $PATCH_SCOPE (use all|embedded|lib|auto)"
    exit 1
    ;;
esac

if [ "$PATCH_SCOPE" = "all" ] || [ "$PATCH_SCOPE" = "lib" ]; then
  require_file "$NEOFORGE_JAR"
fi
if [ "$PATCH_SCOPE" = "all" ] || [ "$PATCH_SCOPE" = "embedded" ] || [ "$PATCH_SCOPE" = "auto" ]; then
  require_file "$ASYNC_JAR"
fi

tmp="$(mktemp -d)"
cleanup() { rm -rf "$tmp"; }
trap cleanup EXIT

patch_list="$tmp/patch_list.txt"

# Moonrise-only entries (classes + resources + mixins)
jar tf "$PATCH_JAR" \
  | rg -e '^ca/spottedleaf/moonrise/' \
        -e '^assets/moonrise/' \
        -e '^moonrise(\\.|-).*mixins\\.json$' \
        -e '^META-INF/services/ca\\.spottedleaf\\.moonrise\\.common\\.PlatformHooks$' \
  | rg -v '/$' \
  | rg -v '^ca/spottedleaf/moonrise/neoforge/MoonriseNeoForge\\.class$' \
  | sort > "$patch_list"

if [ ! -s "$patch_list" ]; then
  echo "No Moonrise entries found in patch jar."
  exit 1
fi

verify_patch() {
  local target="$1"
  local intersect="$2"
  local label="$3"
  local vdir="$tmp/${label}.verify"

  rm -rf "$vdir"
  mkdir -p "$vdir/patch" "$vdir/target"

  (
    cd "$vdir/patch"
    tr '\n' '\0' < "$intersect" | xargs -0 -n 200 jar xf "$PATCH_JAR"
  )

  (
    cd "$vdir/target"
    tr '\n' '\0' < "$intersect" | xargs -0 -n 200 jar xf "$target"
  )

  (
    cd "$vdir/patch"
    find . -type f -print0 | sort -z | xargs -0 sha256sum
  ) > "$vdir/patch.sha"

  (
    cd "$vdir/target"
    find . -type f -print0 | sort -z | xargs -0 sha256sum
  ) > "$vdir/target.sha"

  if diff -u "$vdir/patch.sha" "$vdir/target.sha" >/dev/null; then
    echo "[OK] $label: verification passed."
  else
    echo "[WARN] $label: verification failed. See:"
    echo "  $vdir/patch.sha"
    echo "  $vdir/target.sha"
  fi
}

patch_jar() {
  local target="$1"
  local label="$2"
  local target_list="$tmp/${label}.list"
  local missing="$tmp/${label}.missing"
  local extract_dir="$tmp/${label}.extract"
  local verify_list="$tmp/${label}.verify_list"

  jar tf "$target" | sort > "$target_list"
  comm -23 "$patch_list" "$target_list" > "$missing"

  if [ -s "$missing" ]; then
    echo "[INFO] $label: adding new entries not present in target (showing first 10):"
    head -n 10 "$missing"
  fi

  # shellcheck disable=SC2155
  local backup="${target}.bak.$(date +%Y%m%d_%H%M%S)"
  cp "$target" "$backup"
  echo "[OK] $label: backup created -> $backup"

  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"

  (
    cd "$extract_dir"
    tr '\n' '\0' < "$patch_list" | xargs -0 -n 200 jar xf "$PATCH_JAR"
  )

  ( cd "$extract_dir" && jar uf "$target" -C "$extract_dir" . )

  for entry in "${SERVICE_ENTRIES[@]}"; do
    if jar tf "$PATCH_JAR" | rg -F -x -q "$entry"; then
      if ! jar tf "$target" | rg -F -x -q "$entry"; then
        (
          cd "$extract_dir"
          jar xf "$PATCH_JAR" "$entry"
        )
        jar uf "$target" -C "$extract_dir" "$entry"
        echo "[OK] $label: added service entry $entry"
      fi
    fi
  done

  for entry in "${EXCLUDE_ENTRIES[@]}"; do
    if jar tf "$target" | rg -F -x -q "$entry"; then
      zip -d "$target" "$entry" >/dev/null 2>&1 || true
      echo "[OK] $label: removed excluded entry $entry"
    fi
  done

  cp "$patch_list" "$verify_list"
  for entry in "${EXCLUDE_ENTRIES[@]}"; do
    rg -F -x -v "$entry" "$verify_list" > "${verify_list}.tmp" && mv "${verify_list}.tmp" "$verify_list"
  done

  verify_patch "$target" "$verify_list" "$label"
}

if [ "$PATCH_SCOPE" = "all" ] || [ "$PATCH_SCOPE" = "lib" ]; then
  echo "[STEP] Patching NeoForge library jar..."
  patch_jar "$NEOFORGE_JAR" "neoforge_lib"
fi

if [ "$PATCH_SCOPE" = "all" ] || [ "$PATCH_SCOPE" = "embedded" ] || [ "$PATCH_SCOPE" = "auto" ]; then
  echo "[STEP] Patching embedded NeoForge jar inside AsyncYouer..."
  if jar tf "$ASYNC_JAR" | rg -q "^${EMBEDDED_PATH}$"; then
    mkdir -p "$tmp/async"
    ( cd "$tmp/async" && jar xf "$ASYNC_JAR" "$EMBEDDED_PATH" )

    patch_jar "$tmp/async/$EMBEDDED_PATH" "neoforge_embedded"

    async_backup="${ASYNC_JAR}.bak.$(date +%Y%m%d_%H%M%S)"
    cp "$ASYNC_JAR" "$async_backup"
    echo "[OK] async: backup created -> $async_backup"

    jar uf "$ASYNC_JAR" -C "$tmp/async" "$EMBEDDED_PATH"
    echo "[OK] async: embedded jar updated."
  else
    echo "[WARN] async: embedded jar not found: $EMBEDDED_PATH"
  fi
fi

if [ "$PATCH_SCOPE" = "auto" ]; then
  if [ -z "$SERVER_DIR" ]; then
    SERVER_DIR="$(dirname "$ASYNC_JAR")"
  fi

  lib_targets=()
  if [ -d "$SERVER_DIR/libraries/net/neoforged/neoforge" ]; then
    while IFS= read -r jar; do
      lib_targets+=("$jar")
    done < <(rg --files -g 'neoforge-*-universal.jar' "$SERVER_DIR/libraries/net/neoforged/neoforge" || true)
  fi
  if [ -d "$SERVER_DIR/libraries/com/mohistmc/installation/data" ]; then
    while IFS= read -r jar; do
      lib_targets+=("$jar")
    done < <(rg --files -g 'paper-remap*.jar' "$SERVER_DIR/libraries/com/mohistmc/installation/data" || true)
  fi

  if [ "${#lib_targets[@]}" -eq 0 ]; then
    echo "[INFO] auto: no library jars found under $SERVER_DIR/libraries (run again after first start if needed)."
  else
    idx=0
    for jar in "${lib_targets[@]}"; do
      idx=$((idx + 1))
      echo "[STEP] Auto patching library jar: $jar"
      patch_jar "$jar" "auto_lib_${idx}"
    done
  fi
fi

echo "[DONE] Patching complete."
