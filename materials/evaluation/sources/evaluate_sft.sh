#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$SCRIPT_DIR"
exec bash ./evaluate_samples.sh --tasks 3 --predictions sft/predictions.jsonl
