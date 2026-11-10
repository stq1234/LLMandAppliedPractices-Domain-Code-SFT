#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
LOWCODE_ROOT="$SCRIPT_DIR"
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/apps/jdk-21/bin/javac" ]]; then
  JAVA_HOME="$HOME/apps/jdk-21"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v javac >/dev/null 2>&1; then
  echo "javac is required to build skeletons" >&2
  exit 1
fi

TOOL_DIR="$LOWCODE_ROOT/java-skeleton"
CLASSES_DIR="$TOOL_DIR/build/classes"
REPOS_DIR="$LOWCODE_ROOT/repos"
OUTPUT_ROOT="$LOWCODE_ROOT/skeletons"
mkdir -p "$CLASSES_DIR" "$OUTPUT_ROOT"

javac -encoding UTF-8 -d "$CLASSES_DIR" \
  "$TOOL_DIR/src/JavaSkeletonExtractor.java"

count=0
while IFS= read -r -d '' repo; do
  name="${repo##*/}"
  output_dir="$OUTPUT_ROOT/$name"
  mkdir -p "$output_dir"
  echo "[$name] extracting skeletons..."
  java -cp "$CLASSES_DIR" JavaSkeletonExtractor \
    --repo "$repo" --output "$output_dir/skeletons.jsonl"
  count=$((count + 1))
done < <(find "$REPOS_DIR" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)

echo "Skeleton extraction complete: $count repositories -> $OUTPUT_ROOT"
