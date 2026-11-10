from __future__ import annotations

import csv
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from statistics import median

PROJECT_ROOT = Path(__file__).resolve().parents[1]
ANALYSIS_DIR = Path(__file__).resolve().parent
EVAL_DIR = PROJECT_ROOT / "materials" / "evaluation"
RUNS_DIR = EVAL_DIR / "runs"
RUN_NAMES = {
    "base": "20261109-123917-aixcoder-v2",
    "sft": "20261109-124514-sft-offline",
}
TASK_LABELS = {
    "difficulty1_lines": "snippet",
    "difficulty1_api": "api",
    "difficulty2_ast": "completion",
    "difficulty2_function_body": "generation",
    "difficulty3_files": "synthesis",
}
DOMAINS = ("企业信息化", "智慧物流", "智慧城市", "All domains")


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path):
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def write_csv(path: Path, fieldnames, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def main():
    ANALYSIS_DIR.mkdir(parents=True, exist_ok=True)
    reports = {
        key: load_json(RUNS_DIR / run_name / "report.json")
        for key, run_name in RUN_NAMES.items()
    }
    results = {
        key: {row["id"]: row for row in report["results"]}
        for key, report in reports.items()
    }
    base, sft = results["base"], results["sft"]
    if set(base) != set(sft):
        raise ValueError(f"Run sample sets differ: base={len(base)}, sft={len(sft)}")
    sample_ids = sorted(base)
    if len(sample_ids) != 220:
        raise ValueError(f"Expected 220 paired samples, got {len(sample_ids)}")

    catalog = load_json(EVAL_DIR / "sources" / "repository_catalog.json")
    domain_by_repo = {
        row["repository_full_name"]: row["domain"]
        for row in catalog
        if isinstance(row, dict)
        and isinstance(row.get("repository_full_name"), str)
        and isinstance(row.get("domain"), str)
    }
    for run in results.values():
        for row in run.values():
            if row["repo"] not in domain_by_repo:
                raise ValueError(f"No domain mapping for repo {row['repo']!r}")
    domain_by_id = {
        sample_id: domain_by_repo[base[sample_id]["repo"]]
        for sample_id in sample_ids
    }

    prediction_path = EVAL_DIR / "sources" / "sft" / "predictions.jsonl"
    predictions = list(read_jsonl(prediction_path))
    pred_by_id = {row["id"]: row for row in predictions}
    if len(predictions) != len(pred_by_id):
        raise ValueError("Duplicate ID in downloaded SFT predictions")
    pred_id_by_sample = {
        sample_id: f"{sft[sample_id]['repo']}_{sample_id}"
        for sample_id in sample_ids
    }

    sft_completion_path = RUNS_DIR / RUN_NAMES["sft"] / "completions.jsonl"
    completion_rows = list(read_jsonl(sft_completion_path))
    completion_by_sample = {row["id"]: row for row in completion_rows}
    if len(completion_by_sample) != len(completion_rows):
        raise ValueError("Duplicate ID in SFT completions.jsonl")
    raw_copy_matches = 0
    prediction_rows_present = 0
    prediction_models = Counter()
    exact_gt_ids = []
    base_exact_gt_ids = []
    for sample_id in sample_ids:
        pred = pred_by_id.get(pred_id_by_sample[sample_id])
        if pred is None:
            continue
        prediction_rows_present += 1
        prediction_models[pred.get("model", "")] += 1
        source_gt = sft[sample_id]["sample"]["target_files"][0]["ground_truth"]
        normalized_pred = (pred.get("completion") or "").replace("\r\n", "\n").strip()
        normalized_gt = source_gt.replace("\r\n", "\n").strip()
        if normalized_pred == normalized_gt:
            exact_gt_ids.append(sample_id)
        normalized_base = (base[sample_id].get("raw_model_output") or "").replace("\r\n", "\n").strip()
        if normalized_base == normalized_gt:
            base_exact_gt_ids.append(sample_id)
        run_completion = completion_by_sample.get(sample_id)
        if run_completion and run_completion.get("files"):
            if run_completion["files"][0].get("raw_output") == pred.get("completion"):
                raw_copy_matches += 1

    transitions = Counter(
        (base[sample_id]["status"], sft[sample_id]["status"])
        for sample_id in sample_ids
    )
    if sum(transitions.values()) != 220:
        raise ValueError("Paired status transitions do not sum to 220")

    def status_counts(rows, ids):
        count = Counter(rows[sample_id]["status"] for sample_id in ids)
        return {
            "n": len(ids),
            "passed": count.get("passed", 0),
            "failed": count.get("failed", 0),
            "error": count.get("error", 0),
            "pending": count.get("pending", 0),
        }

    summary_rows = []
    for domain in (*DOMAINS[:3], "All domains"):
        ids = [
            sample_id for sample_id in sample_ids
            if domain == "All domains" or domain_by_id[sample_id] == domain
        ]
        bc = status_counts(base, ids)
        sc = status_counts(sft, ids)
        summary_rows.append({
            "domain": domain,
            "n": len(ids),
            "base_pass": bc["passed"],
            "sft_pass": sc["passed"],
            "base_fail": bc["failed"],
            "sft_fail": sc["failed"],
            "base_error": bc["error"],
            "sft_error": sc["error"],
            "base_pass_rate": f'{100 * bc["passed"] / len(ids):.2f}%',
            "sft_pass_rate": f'{100 * sc["passed"] / len(ids):.2f}%',
            "delta_percentage_points": f'{100 * (sc["passed"] - bc["passed"]) / len(ids):+.2f}',
        })
    write_csv(
        ANALYSIS_DIR / "evaluation_summary.csv",
        [
            "domain", "n", "base_pass", "sft_pass", "base_fail", "sft_fail",
            "base_error", "sft_error", "base_pass_rate", "sft_pass_rate",
            "delta_percentage_points",
        ],
        summary_rows,
    )

    transition_names = (
        ("failed", "passed", "fail_to_pass"),
        ("passed", "failed", "pass_to_fail"),
        ("passed", "error", "pass_to_error"),
        ("passed", "passed", "pass_to_pass"),
        ("failed", "failed", "fail_to_fail"),
        ("failed", "error", "fail_to_error"),
        ("error", "passed", "error_to_pass"),
        ("error", "failed", "error_to_fail"),
        ("error", "error", "error_to_error"),
    )
    transition_rows = []
    for domain in (*DOMAINS[:3], "All domains"):
        ids = [
            sample_id for sample_id in sample_ids
            if domain == "All domains" or domain_by_id[sample_id] == domain
        ]
        row = {"domain": domain, "n": len(ids)}
        for old, new, name in transition_names:
            row[name] = sum(
                base[sample_id]["status"] == old and sft[sample_id]["status"] == new
                for sample_id in ids
            )
        transition_rows.append(row)
    write_csv(
        ANALYSIS_DIR / "paired_transition_summary.csv",
        ["domain", "n", *[name for _, _, name in transition_names]],
        transition_rows,
    )

    paired_rows = []
    sample_index_rows = []
    for sample_id in sample_ids:
        b = base[sample_id]
        s = sft[sample_id]
        bp = b["sample"]["target_files"]
        sp = s["sample"]["target_files"]
        base_tests = b.get("tests", [])
        sft_tests = s.get("tests", [])
        prediction = pred_by_id.get(pred_id_by_sample[sample_id], {})
        sft_raw = (
            prediction.get("completion", "")
            if prediction
            else s.get("raw_model_output", "")
        )
        base_raw = b.get("raw_model_output", "")
        source_path = bp[0].get("source_path", "") if bp else ""
        gt = bp[0].get("ground_truth", "") if bp else b.get("ground_truth", "")
        source_sha = bp[0].get("source_sha256", "") if bp else ""
        paired_rows.append({
            "id": sample_id,
            "domain": domain_by_id[sample_id],
            "repo": b["repo"],
            "repo_tag": b["repo_tag"],
            "target_paths": ";".join(x.get("source_path", "") for x in bp),
            "base_status": b["status"],
            "sft_status": s["status"],
            "base_test_commands": len(base_tests),
            "base_commands_exit_0": sum(t.get("exit_code") == 0 for t in base_tests),
            "base_commands_nonzero": sum(
                t.get("exit_code") is not None and t.get("exit_code") != 0
                for t in base_tests
            ),
            "sft_test_commands": len(sft_tests),
            "sft_commands_exit_0": sum(t.get("exit_code") == 0 for t in sft_tests),
            "sft_commands_nonzero": sum(
                t.get("exit_code") is not None and t.get("exit_code") != 0
                for t in sft_tests
            ),
            "base_error": b.get("error", ""),
            "sft_error": s.get("error", ""),
            "sample_metadata_equal": b.get("sample") == s.get("sample"),
            "ground_truth_equal": b.get("ground_truth") == s.get("ground_truth"),
            "base_output_sha256": sha256(base_raw),
            "sft_source_model": prediction.get("model", ""),
            "sft_raw_output_sha256": sha256(sft_raw),
            "sft_raw_output_chars": len(sft_raw),
            "sft_prediction_present": bool(prediction),
            "sft_run_completion_present": sample_id in completion_by_sample,
        })
        sample_index_rows.append({
            "id": sample_id,
            "repo": b["repo"],
            "repo_tag": b["repo_tag"],
            "domain": domain_by_id[sample_id],
            "target_paths": ";".join(x.get("source_path", "") for x in bp),
            "ground_truth_sha256": sha256(gt),
            "source_sha256": source_sha,
            "only_sft_train": b["sample"].get("only_sft_train"),
            "line_coverage_filter_enabled": b["sample"].get("line_coverage_filter_enabled"),
            "line_coverage_threshold": b["sample"].get("line_coverage_threshold"),
        })
    write_csv(
        ANALYSIS_DIR / "paired_results.csv",
        list(paired_rows[0]),
        paired_rows,
    )
    write_csv(
        ANALYSIS_DIR / "evaluation_sample_index.csv",
        list(sample_index_rows[0]),
        sample_index_rows,
    )

    task_metrics = []
    for run_key, report in reports.items():
        subtasks = report.get("by_subtask_domain", {})
        for subtask, task_name in TASK_LABELS.items():
            groups = subtasks.get(subtask, {})
            for domain in DOMAINS:
                values = groups.get(domain, {})
                if subtask.startswith("difficulty1_"):
                    metrics = values.get("character_metrics", {})
                    numerator = metrics.get("matched_characters", 0)
                    denominator = metrics.get("total_characters", 0)
                    scored = metrics.get("scored_samples", 0)
                    passed = failed = errors = pending = ""
                    value = numerator / denominator if denominator else None
                    metric_name = "CharacterAccuracy"
                else:
                    numerator = values.get("passed", 0)
                    denominator = values.get("total", 0)
                    scored = ""
                    passed = values.get("passed", 0)
                    failed = values.get("failed", 0)
                    errors = values.get("errors", 0)
                    pending = values.get("pending", 0)
                    value = numerator / denominator if denominator else None
                    metric_name = "SamplePassRate"
                task_metrics.append({
                    "run": RUN_NAMES[run_key],
                    "condition": run_key,
                    "task": task_name,
                    "subtask": subtask,
                    "domain": domain,
                    "metric": metric_name,
                    "value": "" if value is None else f"{value:.10f}",
                    "numerator": numerator,
                    "denominator": denominator,
                    "scored_samples": scored,
                    "passed": passed,
                    "failed": failed,
                    "errors": errors,
                    "pending": pending,
                })
    write_csv(
        ANALYSIS_DIR / "task_metrics.csv",
        list(task_metrics[0]),
        task_metrics,
    )

    example_dir = EVAL_DIR / "examples" / "failed"
    example_dir.mkdir(parents=True, exist_ok=True)
    example_specs = {
        "difficulty3_b1a5f27af4b7f4d0": "fail_to_pass_compile_error",
        "difficulty3_32c0a2af7ec1083b": "pass_to_fail_behavior_regression",
        "difficulty3_adf0c54aecaaa590": "output_extraction_error",
    }
    for sample_id, label in example_specs.items():
        b = base[sample_id]
        s = sft[sample_id]
        pred = pred_by_id[pred_id_by_sample[sample_id]]

        def test_evidence(rows):
            evidence = []
            for test in rows:
                output = test.get("output", "")
                lines = [
                    line.strip() for line in output.splitlines()
                    if re.search(
                        r"Tests run:|expected:|but was:|error:|COMPILATION ERROR|BUILD FAILURE|BUILD FAILED|BUILD SUCCESS",
                        line,
                        re.IGNORECASE,
                    )
                ]
                evidence.append({
                    "command": test.get("command", ""),
                    "exit_code": test.get("exit_code"),
                    "timed_out": test.get("timed_out"),
                    "duration_seconds": test.get("duration_seconds"),
                    "excerpt_lines": lines[-12:],
                })
            return evidence

        sample = b["sample"]
        target = sample.get("target_files", [{}])[0]
        example = {
            "id": sample_id,
            "case_type": label,
            "domain": domain_by_id[sample_id],
            "repo": b["repo"],
            "repo_tag": b["repo_tag"],
            "target_path": target.get("source_path", ""),
            "base_status": b["status"],
            "sft_status": s["status"],
            "sft_prediction_model": pred.get("model", ""),
            "ground_truth": target.get("ground_truth", b.get("ground_truth", "")),
            "base_model_output": b.get("model_output", ""),
            "sft_model_output": s.get("model_output", ""),
            "sft_prediction_raw_output": pred.get("completion", ""),
            "base_error": b.get("error", ""),
            "sft_error": s.get("error", ""),
            "base_test_evidence": test_evidence(b.get("tests", [])),
            "sft_test_evidence": test_evidence(s.get("tests", [])),
            "sample_expected_test_commands": len(sample.get("tests", [])),
        }
        (example_dir / f"{sample_id}.json").write_text(
            json.dumps(example, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    all_task_rows = [
        row for row in task_metrics
        if row["domain"] == "All domains"
    ]
    prediction_tokens = [
        int(row["requested_max_tokens"])
        for row in predictions
        if isinstance(row.get("requested_max_tokens"), (int, float))
    ]
    structure_budgets = [
        int(row["structure_token_budget"])
        for row in predictions
        if isinstance(row.get("structure_token_budget"), (int, float))
    ]
    prompt_tokens = [
        int(row["prompt_tokens"])
        for row in predictions
        if isinstance(row.get("prompt_tokens"), (int, float))
    ]

    run_metadata = {}
    for run_key, report in reports.items():
        checkpoint = load_json(
            RUNS_DIR / RUN_NAMES[run_key] / "checkpoint.json"
        )
        rows = list(results[run_key].values())
        statuses = Counter(row["status"] for row in rows)
        all_test_runs = [t for row in rows for t in row.get("tests", [])]
        command_exit = Counter(
            "null" if t.get("exit_code") is None else str(t.get("exit_code"))
            for t in all_test_runs
        )
        run_metadata[run_key] = {
            "run_name": RUN_NAMES[run_key],
            "report_created_at": report.get("created_at"),
            "report_model_field": report.get("model"),
            "report_base_url_field": report.get("base_url"),
            "checkpoint": checkpoint,
            "result_rows": len(rows),
            "unique_sample_ids": len({row["id"] for row in rows}),
            "statuses": dict(statuses),
            "test_command_rows": len(all_test_runs),
            "test_command_exit_codes": dict(command_exit),
            "by_subtask_domain": report.get("by_subtask_domain", {}),
        }

    exact_sample_equal = sum(
        base[sample_id]["sample"] == sft[sample_id]["sample"]
        for sample_id in sample_ids
    )
    exact_gt_equal = sum(
        base[sample_id]["ground_truth"] == sft[sample_id]["ground_truth"]
        for sample_id in sample_ids
    )
    sft_output_count = len(completion_rows)
    error_ids = [
        sample_id for sample_id in sample_ids
        if sft[sample_id]["status"] == "error"
    ]
    summary_json = {
        "scope": {
            "runs": RUN_NAMES,
            "reported_task_names": list(TASK_LABELS.values()),
            "task_metrics_rows": len(task_metrics),
            "scored_subtask": "synthesis",
            "paired_samples": len(sample_ids),
            "domains": DOMAINS,
        },
        "runs": run_metadata,
        "paired": {
            "same_id_set": True,
            "sample_metadata_equal_count": exact_sample_equal,
            "ground_truth_equal_count": exact_gt_equal,
            "status_transitions": {
                f"{old}_to_{new}": transitions.get((old, new), 0)
                for old, new, _ in transition_names
            },
        },
        "sft_offline_prediction_source": {
            "remote_path": "/home/shaotianqi/LowCode/sft/predictions.jsonl",
            "downloaded_path": str(prediction_path),
            "row_count": len(predictions),
            "unique_prediction_ids": len(pred_by_id),
            "model_fields": dict(prediction_models),
            "evaluation_result_fields": dict(Counter(
                row.get("evaluation_result", "") for row in predictions
            )),
            "run_completion_rows": sft_output_count,
            "run_completion_exact_raw_output_matches": raw_copy_matches,
            "prediction_rows_present_for_paired_samples": prediction_rows_present,
            "exact_ground_truth_full_file_copy_count": len(exact_gt_ids),
            "exact_ground_truth_full_file_copy_ids": exact_gt_ids,
            "base_exact_ground_truth_full_file_copy_count": len(base_exact_gt_ids),
            "base_exact_ground_truth_full_file_copy_ids": base_exact_gt_ids,
            "error_sample_ids": error_ids,
            "requested_max_tokens": {
                "min": min(prediction_tokens) if prediction_tokens else None,
                "median": median(prediction_tokens) if prediction_tokens else None,
                "max": max(prediction_tokens) if prediction_tokens else None,
            },
            "structure_token_budget": {
                "min": min(structure_budgets) if structure_budgets else None,
                "median": median(structure_budgets) if structure_budgets else None,
                "max": max(structure_budgets) if structure_budgets else None,
            },
            "prompt_tokens": {
                "min": min(prompt_tokens) if prompt_tokens else None,
                "median": median(prompt_tokens) if prompt_tokens else None,
                "max": max(prompt_tokens) if prompt_tokens else None,
            },
        },
        "task_metrics_all_domains": all_task_rows,
        "interpretation_limits": [
            "Only synthesis has nonzero evaluation rows; snippet, api, completion, and generation are N/A or empty in these runs.",
            "The primary file-level pass rate uses all 220 assigned samples; an error is a non-pass and remains in the denominator.",
            "The SFT predictions are offline records whose model field is aixcoder-7b-v2-sft-0826; this does not identify v4 checkpoint weights.",
            "The evaluator report model/base_url fields are not sufficient to identify the offline predictions' model or prove a live call.",
            "The SFT inference prompt itself is not stored in predictions.jsonl; exact prompt equality with the base run is unverified.",
        ],
    }
    (ANALYSIS_DIR / "evaluation_summary.json").write_text(
        json.dumps(summary_json, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "summary_csv_rows": len(summary_rows),
        "transition_rows": len(transition_rows),
        "paired_rows": len(paired_rows),
        "sample_index_rows": len(sample_index_rows),
        "task_metrics_rows": len(task_metrics),
        "domain_map": domain_by_repo,
        "summary": summary_rows,
        "transitions": transition_rows[-1],
        "sft_prediction_models": dict(prediction_models),
        "sft_prediction_rows": len(predictions),
        "sft_report_completion_rows": sft_output_count,
        "raw_copy_matches": raw_copy_matches,
        "exact_gt_copies": len(exact_gt_ids),
        "exact_gt_ids": exact_gt_ids,
        "base_exact_gt_ids": base_exact_gt_ids,
        "sft_token_budgets": summary_json["sft_offline_prediction_source"]["requested_max_tokens"],
        "error_ids": error_ids,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

