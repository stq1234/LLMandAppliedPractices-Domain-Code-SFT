#!/usr/bin/env python3
"""Report saved prediction results without generating or evaluating samples."""

import argparse
import json
import sys
import time
from collections import Counter
from pathlib import Path

from task_categories import classify_record

from evaluation_reporting import (
    DOMAINS, VALID_STATUSES, TASK_SUBTASKS,
    add_character_metrics, print_grouped_summary,
)

ROOT = Path(__file__).resolve().parent


def read_json(path):
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def load_domains(root):
    catalog = read_json(root / "repository_catalog.json")
    if not isinstance(catalog, list):
        raise ValueError("repository_catalog.json must contain an array")
    domains = {}
    for item in catalog:
        if not isinstance(item, dict):
            raise ValueError("Invalid repository catalog entry")
        repository, domain = item.get("repository_full_name"), item.get("domain")
        if not isinstance(repository, str) or not repository or domain not in DOMAINS:
            raise ValueError(f"Invalid repository domain: {repository!r}, {domain!r}")
        if repository in domains and domains[repository] != domain:
            raise ValueError(f"Conflicting domains for repository {repository}")
        domains[repository] = domain
    return domains


def load_sample_categories(root, tasks):
    """Join prediction IDs to existing sample metadata; no repository checkout is needed."""
    categories = {task: {} for task in tasks}
    for sample_repo in sorted((root / "samples").iterdir()):
        if not sample_repo.is_dir() or not (sample_repo / "metadata.json").is_file():
            continue
        repository = sample_repo.name
        for task in tasks:
            sample_file = sample_repo / f"{task}.json"
            if not sample_file.is_file():
                continue
            samples = read_json(sample_file)
            if not isinstance(samples, list):
                raise ValueError(f"Sample file must contain an array: {sample_file}")
            for sample in samples:
                if not isinstance(sample, dict):
                    raise ValueError(f"Invalid sample object in {sample_file}")
                if task == 3 and sample.get("only_sft_train") is True:
                    continue
                sample_id = sample.get("id")
                if sample.get("repo") != repository or not isinstance(sample_id, str) or not sample_id:
                    raise ValueError(f"Invalid sample identity in {sample_file}: {sample_id!r}")
                subtask = classify_record(sample, task - 1)
                if subtask not in TASK_SUBTASKS[task]:
                    raise ValueError(f"Invalid task classification for {sample_id}")
                prediction_id = f"{repository}_{sample_id}"
                if prediction_id in categories[task]:
                    raise ValueError(f"Duplicate sample ID: {prediction_id}")
                categories[task][prediction_id] = (repository, subtask)
    return categories


def evaluate_results(task=None, root=ROOT):
    root = Path(root)
    tasks = (task,) if task is not None else (1, 2, 3)
    domains = load_domains(root)
    categories = load_sample_categories(root, tasks)
    subtasks = [subtask for selected in tasks for subtask in TASK_SUBTASKS[selected]]
    grouped = {subtask: {domain: Counter() for domain in DOMAINS} for subtask in subtasks}
    seen_ids = set()
    ignored = 0
    for selected in tasks:
        path = root / f"task{selected}_completions.jsonl"
        with path.open(encoding="utf-8") as source:
            for line_number, line in enumerate(source, 1):
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError as error:
                    raise ValueError(f"{path}:{line_number}: {error.msg}") from error
                if not isinstance(record, dict):
                    raise ValueError(f"{path}:{line_number}: expected a prediction object")
                prediction_id, repository = record.get("id"), record.get("namespace")
                if not isinstance(prediction_id, str) or not prediction_id:
                    raise ValueError(f"{path}:{line_number}: missing prediction id")
                if not isinstance(repository, str) or not repository:
                    raise ValueError(f"{path}:{line_number}: missing namespace")
                if prediction_id in seen_ids:
                    raise ValueError(f"Duplicate prediction ID: {prediction_id}")
                seen_ids.add(prediction_id)
                metadata = categories[selected].get(prediction_id)
                language = str(record.get("language") or "").lower()
                if metadata is None or metadata[0] != repository or language not in ("", "java"):
                    ignored += 1
                    continue
                if repository not in domains:
                    raise ValueError(f"Repository missing from catalog: {repository}")
                if selected == 1:
                    add_character_metrics(grouped[metadata[1]][domains[repository]], record)
                    continue
                status = record.get("evaluation_result")
                if not isinstance(status, str) or status not in VALID_STATUSES:
                    status = "pending"
                grouped[metadata[1]][domains[repository]][status] += 1

    print_grouped_summary(grouped)
    if ignored:
        print(f"Ignored={ignored}: predictions outside the selected Java sample set.", file=sys.stderr)
    return 0


def main(argv=None):
    started = time.perf_counter()
    try:
        parser = argparse.ArgumentParser(description="汇报预测文件中已有的测评结果，不执行生成或测评。")
        parser.add_argument("--task", type=int, choices=(1, 2, 3), help="仅汇报任务 1、2 或 3")
        args = parser.parse_args(argv)
        return evaluate_results(args.task, ROOT)
    except (OSError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 2
    finally:
        elapsed = time.perf_counter() - started
        print(f"Total elapsed wall time: {elapsed:.2f} seconds", file=sys.stderr, flush=True)


if __name__ == "__main__":
    sys.exit(main())
