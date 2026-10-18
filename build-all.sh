#!/usr/bin/env bash
# Builds every Minecraft version under versions/ (or only the ones given as arguments)
# and collects the jars into dist/<version>/.
set -u
cd "$(dirname "$0")"
VERSIONS=("$@")
[ ${#VERSIONS[@]} -eq 0 ] && mapfile -t VERSIONS < <(grep -v '^\s*$' versions.txt)
failed=()
for v in "${VERSIONS[@]}"; do
  echo "=== $v"
  dir="versions/$v"
  [ -d "$dir" ] || { echo "missing $dir"; failed+=("$v"); continue; }
  chmod +x "$dir/gradlew"
  if (cd "$dir" && ./gradlew clean build --console=plain); then
    out="dist/$v"; rm -rf "$out"; mkdir -p "$out"
    for m in fabric forge neoforge paper bukkit bungeecord velocity; do
      for j in "$dir/$m/build/libs/"*.jar; do
        [ -f "$j" ] || continue
        case "$j" in *sources*|*javadoc*|*dev*|*slim*|*plain*|*api-stub*) continue;; esac
        cp "$j" "$out/"
      done
    done
    ls "$out"
  else
    failed+=("$v")
  fi
done
if [ ${#failed[@]} -gt 0 ]; then echo "FAILED: ${failed[*]}"; exit 1; fi
echo "All versions built."
