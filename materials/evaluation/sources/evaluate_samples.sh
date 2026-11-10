#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$SCRIPT_DIR"
LOWCODE_ROOT="."
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/apps/jdk-21/bin/javac" ]]; then
  JAVA_HOME="$HOME/apps/jdk-21"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi

MAX_SAMPLES=""
TASKS=""
SKIP_REQUESTED=0
PREDICTIONS_REQUESTED=0
EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --max-samples)
      [[ $# -ge 2 ]] || { echo "--max-samples requires a positive integer" >&2; exit 2; }
      MAX_SAMPLES="$2"; shift 2;;
    --tasks)
      [[ $# -ge 2 ]] || { echo "--tasks requires a comma-separated subset of 1,2,3" >&2; exit 2; }
      TASKS="$2"; shift 2;;
    --generate-only)
      EXTRA_ARGS+=(--generate-only); shift;;
    --predictions)
      PREDICTIONS_REQUESTED=1
      shift
      [[ $# -gt 0 && "$1" != --* ]] || { echo "--predictions requires one or more JSONL files" >&2; exit 2; }
      EXTRA_ARGS+=(--predictions)
      while [[ $# -gt 0 && "$1" != --* ]]; do
        EXTRA_ARGS+=("$1"); shift
      done;;
    --skip)
      SKIP_REQUESTED=1
      EXTRA_ARGS+=(--skip); shift;;
    --no-print-results)
      EXTRA_ARGS+=(--no-print-results); shift;;
    *)
      if [[ -z "$MAX_SAMPLES" && "$1" =~ ^[1-9][0-9]*$ ]]; then MAX_SAMPLES="$1"; shift
      else echo "Usage: $0 [max-samples-per-repository-task] [--tasks 1,2,3] [--generate-only | --predictions FILE [FILE ...]] [--skip] [--no-print-results]" >&2; exit 2; fi;;
  esac
done
if [[ -n "$MAX_SAMPLES" && ! "$MAX_SAMPLES" =~ ^[1-9][0-9]*$ ]]; then
  echo "--max-samples must be a positive integer" >&2
  exit 2
fi
if [[ "$SKIP_REQUESTED" -eq 1 && "$PREDICTIONS_REQUESTED" -ne 1 ]]; then
  echo "--skip requires --predictions" >&2
  exit 2
fi

EVALUATOR_DIR="java-evaluator"
CLASSES_DIR="$EVALUATOR_DIR/build/classes"
TEMP_DIR="$EVALUATOR_DIR/build/tmp"
mkdir -p "$CLASSES_DIR" "$TEMP_DIR"

javac -encoding UTF-8 \
  -d "$CLASSES_DIR" \
  "java-common/src/JavaBuildTool.java" \
  "$EVALUATOR_DIR/src/JavaBenchmarkEvaluator.java"

ARGS=(--root "$LOWCODE_ROOT")
if [[ -n "$MAX_SAMPLES" ]]; then
  ARGS+=(--max-samples "$MAX_SAMPLES")
fi
if [[ -n "$TASKS" ]]; then
  ARGS+=(--tasks "$TASKS")
fi
ARGS+=("${EXTRA_ARGS[@]}")

exec java -Djava.io.tmpdir="$SCRIPT_DIR/$TEMP_DIR" -cp "$CLASSES_DIR" JavaBenchmarkEvaluator "${ARGS[@]}"
