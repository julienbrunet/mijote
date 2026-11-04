#!/usr/bin/env bash
# Increment the highest stable version for one component, independently of CI runs.
set -euo pipefail
component="${1:?Usage: next-version.sh <android|server>}"
case "$component" in
  android|server) ;;
  *) echo "Unknown component: $component" >&2; exit 1 ;;
esac
latest=$(git tag --list "${component}-v*" \
  | sed -nE "s/^${component}-v([0-9]+\.[0-9]+\.[0-9]+)$/\1/p" \
  | sort -V | tail -n 1)
IFS=. read -r major minor patch <<< "${latest:-0.1.0}"
printf '%d.%d.%d\n' "$((10#$major))" "$((10#$minor))" "$((10#$patch + 1))"
