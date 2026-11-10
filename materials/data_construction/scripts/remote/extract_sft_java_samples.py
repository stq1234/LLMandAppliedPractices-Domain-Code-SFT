#!/usr/bin/env python3
"""Extract Java task-3 samples selected by sft/eval.jsonl."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from collections import OrderedDict
from pathlib import Path
from typing import Any


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"invalid JSON in {path}:{line_number}: {error}") from error
            if not isinstance(value, dict):
                raise ValueError(f"expected an object in {path}:{line_number}")
            records.append(value)
    return records


def discover_task3_samples(samples_root: Path) -> dict[str, tuple[str, dict[str, Any]]]:
    by_full_id: dict[str, tuple[str, dict[str, Any]]] = {}
    for repository_dir in sorted(path for path in samples_root.iterdir() if path.is_dir()):
        task3_file = repository_dir / "3.json"
        if not task3_file.is_file():
            continue
        samples = json.loads(task3_file.read_text(encoding="utf-8"))
        if not isinstance(samples, list):
            raise ValueError(f"expected a JSON array in {task3_file}")
        repository = repository_dir.name
        for index, sample in enumerate(samples):
            if not isinstance(sample, dict):
                raise ValueError(f"expected an object in {task3_file}[{index}]")
            sample_id = sample.get("id")
            if not isinstance(sample_id, str) or not sample_id:
                raise ValueError(f"missing sample id in {task3_file}[{index}]")
            full_id = f"{repository}_{sample_id}"
            if full_id in by_full_id:
                raise ValueError(f"duplicate task-3 sample id: {full_id}")
            by_full_id[full_id] = (repository, sample)
    return by_full_id


def write_output(
    output_root: Path,
    grouped_samples: OrderedDict[str, list[dict[str, Any]]],
) -> None:
    if output_root.exists():
        raise FileExistsError(
            f"output directory already exists; refusing to overwrite it: {output_root}"
        )
    output_root.parent.mkdir(parents=True, exist_ok=True)
    temporary_root = Path(tempfile.mkdtemp(prefix=f".{output_root.name}.", dir=output_root.parent))
    try:
        for repository, samples in grouped_samples.items():
            repository_dir = temporary_root / repository
            repository_dir.mkdir()
            destination = repository_dir / "3.json"
            with destination.open("w", encoding="utf-8", newline="\n") as handle:
                json.dump(samples, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
        os.replace(temporary_root, output_root)
    except BaseException:
        for path in sorted(temporary_root.rglob("*"), reverse=True):
            if path.is_file() or path.is_symlink():
                path.unlink()
            elif path.is_dir():
                path.rmdir()
        temporary_root.rmdir()
        raise


def parse_args() -> argparse.Namespace:
    lowcode_root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(
        description="Extract Java task-3 samples selected by sft/eval.jsonl."
    )
    parser.add_argument("--eval", type=Path, default=lowcode_root / "sft/eval.jsonl")
    parser.add_argument("--samples", type=Path, default=lowcode_root / "samples")
    parser.add_argument(
        "--output",
        type=Path,
        default=lowcode_root / "sft_samples_origin",
    )
    return parser.parse_args()


def main() -> int:
    arguments = parse_args()
    eval_path = arguments.eval.expanduser().resolve()
    samples_root = arguments.samples.expanduser().resolve()
    output_root = arguments.output.expanduser().resolve()

    eval_records = read_jsonl(eval_path)
    original_by_id = discover_task3_samples(samples_root)
    grouped_samples: OrderedDict[str, list[dict[str, Any]]] = OrderedDict()
    selected_ids: set[str] = set()

    for index, record in enumerate(eval_records):
        sample_id = record.get("id")
        if not isinstance(sample_id, str) or "difficulty3" not in sample_id:
            continue
        if sample_id in selected_ids:
            raise ValueError(f"duplicate Java eval id at record {index}: {sample_id}")
        selected_ids.add(sample_id)
        matched = original_by_id.get(sample_id)
        if matched is None:
            raise KeyError(f"Java eval sample was not found in samples/*/3.json: {sample_id}")
        repository, original_sample = matched
        namespace = record.get("namespace")
        if namespace is not None and namespace != repository:
            raise ValueError(
                f"namespace mismatch for {sample_id}: eval={namespace!r}, samples={repository!r}"
            )
        grouped_samples.setdefault(repository, []).append(original_sample)

    if not selected_ids:
        raise ValueError(f"no Java difficulty3 records found in {eval_path}")
    write_output(output_root, grouped_samples)

    print(f"Extracted {len(selected_ids)} Java task-3 sample(s) into {output_root}")
    for repository, samples in grouped_samples.items():
        print(f"  {repository}: {len(samples)} sample(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
