#!/usr/bin/env bash
# Per-cell compile gate for the Hytale lane.
#
# Usage (from the repo root):
#   bash scripts/hytale-compile-check.sh hytale/src/main/java/network/vonix/guardian/hytale/capture
#
# Compiles ONLY the given source roots (plus the frozen `spi` package) against the
# real Hytale server API jar and the prebuilt bundled classpath. Output goes to a
# per-run directory under .buildlogs/cc/ so parallel workers never share class
# files and never touch hytale/build/.
#
# Exit 0 = zero javac errors. Any javac error is a real defect in your cell.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK="${HYTALE_JDK:-C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot}"
API_JAR="${HYTALE_API_JAR:-C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar}"
BUNDLED_JAR="${HYTALE_BUNDLED_JAR:-$REPO_ROOT/hytale/build/libs/vonixguardian-hytale-2.2.1.jar}"
SRC_ROOT="$REPO_ROOT/hytale/src/main/java"

[ -x "$JDK/bin/javac" ] || [ -f "$JDK/bin/javac.exe" ] || { echo "COMPILE_GATE=ERROR missing jdk at $JDK"; exit 2; }
[ -f "$API_JAR" ] || { echo "COMPILE_GATE=ERROR missing Hytale API jar at $API_JAR"; exit 2; }
[ -f "$BUNDLED_JAR" ] || { echo "COMPILE_GATE=ERROR missing bundled classpath jar at $BUNDLED_JAR (parent must build first)"; exit 2; }
[ "$#" -ge 1 ] || { echo "COMPILE_GATE=ERROR no source roots given"; exit 2; }

OUT="$REPO_ROOT/.buildlogs/cc/$(date +%s)-$$"
mkdir -p "$OUT/classes"
CP="$API_JAR;$BUNDLED_JAR;$REPO_ROOT/hytale/build/classes/java/main"

# Expand each argument (file or directory) into a list of .java files.
FILES=()
for arg in "$@"; do
  if [ -d "$arg" ]; then
    while IFS= read -r f; do FILES+=("$f"); done < <(find "$arg" -name '*.java' -type f | sort)
  elif [ -f "$arg" ]; then
    FILES+=("$arg")
  else
    echo "COMPILE_GATE=ERROR not a file or directory: $arg"; exit 2
  fi
done
[ "${#FILES[@]}" -ge 1 ] || { echo "COMPILE_GATE=ERROR no .java files found in given roots"; exit 2; }

echo "COMPILE_GATE=START roots=$* files=${#FILES[@]}"
"$JDK/bin/javac" -nowarn -encoding UTF-8 --release 25 \
  -cp "$CP" -sourcepath "$SRC_ROOT" -d "$OUT/classes" "${FILES[@]}" 2>&1 | tee "$OUT/javac.log"
rc=${PIPESTATUS[0]}
if [ "$rc" -eq 0 ]; then
  echo "COMPILE_GATE=PASS classes=$(find "$OUT/classes" -name '*.class' | wc -l) log=$OUT/javac.log"
else
  echo "COMPILE_GATE=FAIL errors=$(grep -c 'error:' "$OUT/javac.log") log=$OUT/javac.log"
fi
exit "$rc"
