#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage: $(basename "$0") <path-to-dmg> [--arch <architecture>] [--build-type <buildType>] [--include-git-hash]

Renames the provided DMG so it follows the unified naming scheme, e.g.
  app-universal-debug-v1.1.2.dmg

Arguments:
  <path-to-dmg>          Path to the DMG produced by the build system.
  --arch <architecture>  Target architecture segment (default: universal).
  --build-type <type>    Build type segment such as debug or release (default: debug).
  --include-git-hash     Append the current git commit hash to the version portion.
USAGE
}

if [[ ${#} -lt 1 ]]; then
  usage
  exit 1
fi

DMG_PATH=$1
shift || true

ARCH="universal"
BUILD_TYPE="debug"
INCLUDE_GIT_HASH=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch)
      ARCH=${2:-}
      shift 2
      ;;
    --build-type)
      BUILD_TYPE=${2:-}
      shift 2
      ;;
    --include-git-hash)
      INCLUDE_GIT_HASH=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ ! -f "$DMG_PATH" ]]; then
  echo "DMG not found: $DMG_PATH" >&2
  exit 1
fi

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
VERSION_FILE="$ROOT_DIR/config/version.properties"

if [[ ! -f "$VERSION_FILE" ]]; then
  echo "Version file missing at $VERSION_FILE" >&2
  exit 1
fi

major=$(grep '^major=' "$VERSION_FILE" | cut -d'=' -f2)
minor=$(grep '^minor=' "$VERSION_FILE" | cut -d'=' -f2)
patch=$(grep '^patch=' "$VERSION_FILE" | cut -d'=' -f2)
metadata=$(grep '^metadata=' "$VERSION_FILE" | cut -d'=' -f2-)

version_base="${major}.${minor}.${patch}"
metadata_clean=$(echo "$metadata" | tr -d '\n')
version="$version_base"

if [[ -n "$metadata_clean" ]]; then
  version+="-$(echo "$metadata_clean" | sed 's/[^A-Za-z0-9._-]/-/g')"
fi

if [[ "$INCLUDE_GIT_HASH" == "true" ]]; then
  if git -C "$ROOT_DIR" rev-parse --short HEAD >/dev/null 2>&1; then
    git_hash=$(git -C "$ROOT_DIR" rev-parse --short HEAD)
    version+="-g$git_hash"
  else
    echo "Warning: Unable to determine git hash; continuing without it" >&2
  fi
fi

filename="app-${ARCH}-${BUILD_TYPE}-v$(echo "$version" | sed 's/[^A-Za-z0-9._-]/-/g').dmg"

output_path="$(dirname "$DMG_PATH")/$filename"

mv "$DMG_PATH" "$output_path"

echo "Renamed DMG to $output_path"
