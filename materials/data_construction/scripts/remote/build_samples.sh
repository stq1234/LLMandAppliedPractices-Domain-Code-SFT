#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
LOWCODE_ROOT="$SCRIPT_DIR"
REPOS_DIR="$LOWCODE_ROOT/repos"
SKELETONS_DIR="$LOWCODE_ROOT/skeletons"
REPOSITORY_CATALOG="$LOWCODE_ROOT/repository_catalog.json"
exec 9>"$LOWCODE_ROOT/.build_samples.lock"
if ! flock -n 9; then
  echo "Another sample build is already running" >&2
  exit 1
fi

if [[ -z "${JAVA_HOME:-}" && -x "$HOME/apps/jdk-21/bin/javac" ]]; then
  JAVA_HOME="$HOME/apps/jdk-21"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi

TASKS="1,2,3"
MIX=false
COVER=false
POSITIONAL_TASKS=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tasks)
      [[ $# -ge 2 ]] || { echo "--tasks requires a comma-separated subset of 1,2,3" >&2; exit 2; }
      TASKS="$2"
      POSITIONAL_TASKS=true
      shift 2
      ;;
    --mix)
      MIX=true
      shift
      ;;
    --cover)
      COVER=true
      shift
      ;;
    -* )
      echo "Unknown option: $1" >&2
      exit 2
      ;;
    *)
      if [[ "$POSITIONAL_TASKS" == true ]]; then
        echo "Tasks were specified more than once" >&2
        exit 2
      fi
      TASKS="$1"
      POSITIONAL_TASKS=true
      shift
      ;;
  esac
done
if [[ ! "$TASKS" =~ ^(1|2|3|difficulty1|difficulty2|difficulty3)(,(1|2|3|difficulty1|difficulty2|difficulty3))*$ ]]; then
  echo "Usage: $0 [--tasks tasks] [--mix] [--cover]" >&2
  exit 2
fi
NORMALIZED_TASKS="$(sed -E 's/difficulty//g' <<<"$TASKS")"

task_selected() {
  [[ ",$NORMALIZED_TASKS," == *",$1,"* ]]
}

BUILDER_DIR="$LOWCODE_ROOT/java-sampler"
CLASSES_DIR="$BUILDER_DIR/build/classes"
SAMPLES_DIR="$LOWCODE_ROOT/samples"
ARCHIVE="$LOWCODE_ROOT/task3-samples-draft.zip"
ARCHIVE_UPLOAD="$LOWCODE_ROOT/task3-samples-draft.zip.upload"
STAGING_DIR="$(mktemp -d "$LOWCODE_ROOT/samples.rebuild.XXXXXX")"
trap 'rm -rf -- "$STAGING_DIR"; rm -f -- "$ARCHIVE_UPLOAD"' EXIT
mkdir -p "$CLASSES_DIR"
if [[ -d "$SAMPLES_DIR" ]]; then
  cp -a -- "$SAMPLES_DIR/." "$STAGING_DIR/"
fi

javac -encoding UTF-8 \
  -d "$CLASSES_DIR" \
  "$LOWCODE_ROOT/java-common/src/JavaBuildTool.java" \
  "$BUILDER_DIR/src/CoverageAnalyzer.java" \
  "$BUILDER_DIR/src/JavaSampleBuilder.java"

JAVA_ARGUMENTS=(
  --repos "$REPOS_DIR"
  --samples "$STAGING_DIR"
  --tasks "$NORMALIZED_TASKS"
)
if task_selected 3; then
  JAVA_ARGUMENTS+=(
    --skeletons "$SKELETONS_DIR"
    --archive "$ARCHIVE_UPLOAD"
    --repository-catalog "$REPOSITORY_CATALOG"
  )
fi
if [[ "$MIX" == true ]]; then
  JAVA_ARGUMENTS+=(--mix)
fi
if [[ "$COVER" == true ]]; then
  JAVA_ARGUMENTS+=(--cover)
fi
java -cp "$CLASSES_DIR" JavaSampleBuilder "${JAVA_ARGUMENTS[@]}"
rm -rf -- "$STAGING_DIR/.coverage-work"

rm -rf -- "$SAMPLES_DIR"
mv -- "$STAGING_DIR" "$SAMPLES_DIR"
if task_selected 3; then
  mv -- "$ARCHIVE_UPLOAD" "$ARCHIVE"
  echo "Task-3 draft archive: $ARCHIVE"
fi
trap - EXIT

echo "Updated selected tasks ($NORMALIZED_TASKS): $SAMPLES_DIR"
