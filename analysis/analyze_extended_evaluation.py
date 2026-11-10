from __future__ import annotations

"""Offline stage, source-length, and aggregation analysis for the paired Java-file evaluation."""

import csv
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


ANALYSIS_DIR = Path(__file__).resolve().parent
PROJECT_DIR = ANALYSIS_DIR.parent
EVAL_DIR = PROJECT_DIR / "materials" / "evaluation"
RUNS = {
    "base": "20261109-123917-aixcoder-v2",
    "sft_offline": "20261109-124514-sft-offline",
}
CONDITION_LABELS = {
    "base": "基础组（AiXcoder 7B V2）",
    "sft_offline": "领域微调预测（历史版本0826）",
}
GRADLE_TEST_TASK_RE = re.compile(
    r"^>[ \t]*Task[ \t]+\S+:test(?:[ \t]+([^\r\n]*))?[ \t]*$",
    re.MULTILINE,
)
SUREFIRE_RE = re.compile(
    r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)",
    re.IGNORECASE,
)
GRADLE_COMPLETED_RE = re.compile(r"\b(\d+)\s+tests?\s+completed\b", re.IGNORECASE)
GRADLE_CORE_SUMMARY_RE = re.compile(
    r"Core test\s+\S+:test\s+summary:\s*(?:SUCCESS|FAILED)\s*\((\d+)\s+tests?\b",
    re.IGNORECASE,
)
NO_TESTS_RE = re.compile(
    r"No tests? (?:to run|were found|found|executed)|No tests matching",
    re.IGNORECASE,
)


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if fieldnames is None:
        fieldnames = list(rows[0]) if rows else []
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def nearest_rank(values: list[int], quantile: float) -> int:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(quantile * len(ordered)) - 1)]


def load_reports() -> dict[str, dict[str, dict[str, Any]]]:
    reports: dict[str, dict[str, dict[str, Any]]] = {}
    for condition, run_name in RUNS.items():
        report_path = EVAL_DIR / "runs" / run_name / "report.json"
        payload = json.loads(report_path.read_text(encoding="utf-8"))
        rows = payload.get("results", [])
        if len(rows) != 220:
            raise ValueError(f"Expected 220 results in {report_path}, found {len(rows)}")
        by_id = {row["id"]: row for row in rows}
        if len(by_id) != 220:
            raise ValueError(f"Duplicate sample IDs in {report_path}")
        reports[condition] = by_id
    if set(reports["base"]) != set(reports["sft_offline"]):
        raise ValueError("Paired reports do not contain identical sample IDs")
    return reports


def load_source_lengths() -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    eval_rows = read_csv(ANALYSIS_DIR / "evaluation_sample_index.csv")
    sft_rows = read_csv(
        PROJECT_DIR / "materials" / "data_construction" / "analysis" / "sft_source_index.csv"
    )
    v4_rows = read_csv(PROJECT_DIR / "materials" / "training" / "data" / "v4_sample_index.csv")

    sft_by_id = {row["id"]: row for row in sft_rows}
    v4_java_by_sha: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in v4_rows:
        if row.get("language", "").lower() == "java":
            v4_java_by_sha[row["source_sha256_exact"]].append(row)

    mapped: dict[str, dict[str, Any]] = {}
    for row in eval_rows:
        sample_id = row["id"]
        source_record_id = f"{row['repo']}_{sample_id}"
        source_record = sft_by_id.get(source_record_id)
        if source_record is None:
            raise ValueError(f"No source-index row for evaluation sample {sample_id}")
        if source_record["source_sha256"] != row["source_sha256"]:
            raise ValueError(f"Evaluation/source-index hash mismatch for {sample_id}")
        if source_record["sft_split"] != "eval":
            raise ValueError(f"Expected the evaluation split for {sample_id}")
        matches = v4_java_by_sha.get(row["source_sha256"], [])
        if len(matches) != 1:
            raise ValueError(f"Expected one exact Java V4 source match for {sample_id}, found {len(matches)}")
        v4 = matches[0]
        if v4["split"] != "test":
            raise ValueError(f"Expected V4 Java test split for {sample_id}, found {v4['split']}")
        if v4["source_sha256_exact"] != row["source_sha256"]:
            raise ValueError(f"V4 exact-source hash mismatch for {sample_id}")
        mapped[sample_id] = {
            **row,
            "source_record_id": source_record_id,
            "v4_sample_id": v4["id"],
            "v4_split": v4["split"],
            "source_lines": int(v4["source_lines"]),
            "source_chars": int(v4["source_chars"]),
        }
    if len(eval_rows) != 220 or len(mapped) != 220:
        raise ValueError(f"Expected 220 unique length mappings, found {len(mapped)}")

    values = [row["source_lines"] for row in mapped.values()]
    p33 = nearest_rank(values, 1 / 3)
    p67 = nearest_rank(values, 2 / 3)
    if p33 >= p67:
        raise ValueError("Source-length quantile thresholds are not ordered")
    for row in mapped.values():
        lines = row["source_lines"]
        row["length_group"] = "short" if lines <= p33 else "medium" if lines <= p67 else "long"

    mapping_info = {
        "evaluation_samples": len(eval_rows),
        "unique_source_records": len(sft_by_id),
        "v4_java_test_matches": len(mapped),
        "source_line_definition": "v4_sample_index.source_lines = len(source_text.split('\\n')); includes blank/comment lines and a trailing empty segment when the source ends with LF",
        "lower_tertile_nearest_rank": p33,
        "upper_tertile_nearest_rank": p67,
        "min_source_lines": min(values),
        "median_source_lines_nearest_rank": nearest_rank(values, 0.5),
        "max_source_lines": max(values),
        "source_hash_join": "evaluation_sample_index.source_sha256 = sft_source_index.source_sha256 = v4_sample_index.source_sha256_exact",
    }
    return mapped, mapping_info


def inspect_test_stage(result: dict[str, Any]) -> dict[str, Any]:
    """Read captured command output; never infer test entry from first-failure labels."""
    tests = result.get("tests") or []
    stage_result = False
    noncached_task_or_summary = False
    stage_without_noncached_evidence = False
    no_tests_notice = False
    surefire_positive_summaries = 0
    gradle_test_task_records = 0
    gradle_cached_records = 0
    gradle_up_to_date_records = 0
    gradle_noncached_records = 0

    for test in tests:
        output = test.get("output", "") or ""
        no_tests_notice = no_tests_notice or bool(NO_TESTS_RE.search(output))
        for match in GRADLE_TEST_TASK_RE.finditer(output):
            outcome = (match.group(1) or "").strip().upper()
            if outcome in {"NO-SOURCE", "SKIPPED"}:
                continue
            stage_result = True
            gradle_test_task_records += 1
            if outcome == "FROM-CACHE":
                gradle_cached_records += 1
            elif outcome == "UP-TO-DATE":
                gradle_up_to_date_records += 1
            else:
                noncached_task_or_summary = True
                gradle_noncached_records += 1

        for match in SUREFIRE_RE.finditer(output):
            if int(match.group(1)) > 0:
                stage_result = True
                noncached_task_or_summary = True
                surefire_positive_summaries += 1

        if any(int(match.group(1)) > 0 for match in GRADLE_COMPLETED_RE.finditer(output)):
            stage_result = True
        if any(int(match.group(1)) > 0 for match in GRADLE_CORE_SUMMARY_RE.finditer(output)):
            stage_result = True

    stage_without_noncached_evidence = stage_result and not noncached_task_or_summary
    return {
        "test_stage_result": stage_result,
        "noncached_task_or_summary": noncached_task_or_summary,
        "stage_without_noncached_evidence": stage_without_noncached_evidence,
        "no_tests_notice": no_tests_notice,
        "test_command_records": len(tests),
        "surefire_positive_summaries": surefire_positive_summaries,
        "gradle_test_task_records": gradle_test_task_records,
        "gradle_cached_test_task_records": gradle_cached_records,
        "gradle_up_to_date_test_task_records": gradle_up_to_date_records,
        "gradle_noncached_test_task_records": gradle_noncached_records,
    }


def extracted_source(result: dict[str, Any]) -> bool:
    output = result.get("model_output")
    return isinstance(output, str) and bool(output.strip())


def all_commands_successful(result: dict[str, Any]) -> bool:
    tests = result.get("tests") or []
    return (
        result.get("status") == "passed"
        and bool(tests)
        and all(not test.get("timed_out", False) and test.get("exit_code") == 0 for test in tests)
    )


def paired_status(base_status: str, sft_status: str) -> str:
    return {
        ("failed", "passed"): "fail_to_pass",
        ("failed", "failed"): "fail_to_fail",
        ("passed", "failed"): "pass_to_fail",
        ("passed", "error"): "pass_to_error",
        ("passed", "passed"): "pass_to_pass",
    }.get((base_status, sft_status), f"{base_status}_to_{sft_status}")


def configure_plot_style() -> None:
    plt.rcParams.update(
        {
            "font.family": "Microsoft YaHei",
            "axes.unicode_minus": False,
            "font.size": 10,
            "axes.titlesize": 14,
            "axes.labelsize": 11,
            "legend.fontsize": 9,
            "figure.dpi": 150,
            "savefig.dpi": 300,
            "svg.fonttype": "none",
        }
    )


def save_figure(fig: Any, name: str) -> None:
    figure_dir = ANALYSIS_DIR / "figures"
    figure_dir.mkdir(parents=True, exist_ok=True)
    fig.savefig(figure_dir / f"{name}.png", dpi=300, bbox_inches="tight")
    fig.savefig(figure_dir / f"{name}.svg", bbox_inches="tight")
    plt.close(fig)


def build_stage_figure(stage_summary: list[dict[str, Any]]) -> None:
    configure_plot_style()
    index = {row["condition"]: row for row in stage_summary}
    conditions = ["base", "sft_offline"]
    labels = ["候选源码可提取", "至少一条测试任务结果\n（含缓存结果）", "全部关联命令成功"]
    counts = {
        "base": [
            index["base"]["candidate_extracted"],
            index["base"]["test_task_result_samples"],
            index["base"]["all_commands_success"],
        ],
        "sft_offline": [
            index["sft_offline"]["candidate_extracted"],
            index["sft_offline"]["test_task_result_samples"],
            index["sft_offline"]["all_commands_success"],
        ],
    }
    fresh = {
        condition: [
            index[condition]["candidate_extracted"],
            index[condition]["noncached_task_or_summary_samples"],
            index[condition]["all_commands_success"],
        ]
        for condition in conditions
    }
    colors = {"base": "#4C78A8", "sft_offline": "#F28E2B"}
    positions = list(range(len(labels)))
    width = 0.34
    fig, ax = plt.subplots(figsize=(9.2, 5.3))
    for offset, condition in [(-width / 2, "base"), (width / 2, "sft_offline")]:
        bars = ax.bar(
            [value + offset for value in positions],
            counts[condition],
            width,
            color=colors[condition],
            label=CONDITION_LABELS[condition],
        )
        for position, bar, n, fresh_n in zip(positions, bars, counts[condition], fresh[condition]):
            rate = 100 * n / 220
            suffix = f"\n含非缓存执行记录\n{fresh_n}" if position == 1 else ""
            ax.text(
                bar.get_x() + bar.get_width() / 2,
                bar.get_height() + 3,
                f"{n}/220\n{rate:.1f}%" + suffix,
                ha="center",
                va="bottom",
                fontsize=8.5,
            )
    ax.set_xticks(positions, labels)
    ax.set_ylabel("目标文件数（每组 n=220）")
    ax.set_ylim(0, 250)
    ax.set_title("文件级评测的提取、测试阶段与命令成功结果")
    ax.grid(axis="y", alpha=0.22)
    ax.set_axisbelow(True)
    ax.legend(frameon=False, loc="upper right")
    fig.text(
        0.5,
        0.015,
        "测试阶段包含构建工具返回的缓存结果；另列至少有一条非缓存任务或正数测试摘要的文件数，统计单位均为文件。",
        ha="center",
        fontsize=8.5,
    )
    fig.tight_layout(rect=(0, 0.06, 1, 1))
    save_figure(fig, "evaluation_stage_funnel")


def build_length_figure(length_rows: list[dict[str, Any]]) -> None:
    configure_plot_style()
    order = ["short", "medium", "long"]
    labels = {
        "short": "短（≤71）",
        "medium": "中（72–134）",
        "long": "长（>134）",
    }
    rows = {row["length_group"]: row for row in length_rows}
    positions = list(range(len(order)))
    width = 0.34
    colors = {"base": "#4C78A8", "sft_offline": "#F28E2B"}
    fig, ax = plt.subplots(figsize=(8.8, 5.3))
    for offset, condition in [(-width / 2, "base"), (width / 2, "sft_offline")]:
        rate_key = "base_pass_rate_pct" if condition == "base" else "sft_pass_rate_pct"
        count_key = "base_pass_count" if condition == "base" else "sft_pass_count"
        rates = [rows[group][rate_key] for group in order]
        bars = ax.bar(
            [value + offset for value in positions],
            rates,
            width,
            color=colors[condition],
            label=CONDITION_LABELS[condition],
        )
        for group, bar, rate in zip(order, bars, rates):
            n = rows[group]["n_samples"]
            numerator = rows[group][count_key]
            ax.text(
                bar.get_x() + bar.get_width() / 2,
                bar.get_height() + 1.8,
                f"{numerator}/{n}\n{rate:.1f}%",
                ha="center",
                va="bottom",
                fontsize=9,
            )
    ax.set_xticks(
        positions,
        [f"{labels[group]} 行\nn={rows[group]['n_samples']}" for group in order],
    )
    ax.set_ylabel("文件级通过率（%）")
    ax.set_ylim(0, 108)
    ax.set_title("配对通过率与目标源码长度分层")
    ax.grid(axis="y", alpha=0.22)
    ax.set_axisbelow(True)
    ax.legend(frameon=False, loc="upper right")
    fig.text(
        0.5,
        0.015,
        "源码行数按换行符分段（包含空白行和注释）；分组依据 220 个样本的三分位边界确定。",
        ha="center",
        fontsize=8.5,
    )
    fig.tight_layout(rect=(0, 0.06, 1, 1))
    save_figure(fig, "source_length_paired_rates")


def main() -> None:
    reports = load_reports()
    source_rows, mapping_info = load_source_lengths()
    if set(source_rows) != set(reports["base"]):
        raise ValueError("Source-length index and evaluation reports do not contain the same IDs")

    sample_rows: list[dict[str, Any]] = []
    stage_events: dict[str, dict[str, dict[str, Any]]] = {condition: {} for condition in RUNS}
    for sample_id, metadata in source_rows.items():
        base = reports["base"][sample_id]
        sft = reports["sft_offline"][sample_id]
        base_stage = inspect_test_stage(base)
        sft_stage = inspect_test_stage(sft)
        stage_events["base"][sample_id] = base_stage
        stage_events["sft_offline"][sample_id] = sft_stage
        base_extracted = extracted_source(base)
        sft_extracted = extracted_source(sft)
        base_pass = all_commands_successful(base)
        sft_pass = all_commands_successful(sft)

        if base["status"] == "passed" and not base_stage["test_stage_result"]:
            raise ValueError(f"Passed base sample lacks positive test-stage evidence: {sample_id}")
        if sft["status"] == "passed" and not sft_stage["test_stage_result"]:
            raise ValueError(f"Passed SFT sample lacks positive test-stage evidence: {sample_id}")
        if base_pass != (base["status"] == "passed") or sft_pass != (sft["status"] == "passed"):
            raise ValueError(f"Report status and command outcomes disagree for {sample_id}")
        if base["test_count"] != len(base.get("tests", [])):
            raise ValueError(f"Base test_count is not command-record count for {sample_id}")
        if sft["test_count"] != len(sft.get("tests", [])):
            raise ValueError(f"SFT test_count is not command-record count for {sample_id}")

        sample_rows.append(
            {
                "id": sample_id,
                "repo": metadata["repo"],
                "repo_tag": metadata["repo_tag"],
                "domain": metadata["domain"],
                "target_paths": metadata["target_paths"],
                "source_sha256": metadata["source_sha256"],
                "source_record_id": metadata["source_record_id"],
                "v4_sample_id": metadata["v4_sample_id"],
                "v4_split": metadata["v4_split"],
                "source_lines": metadata["source_lines"],
                "source_chars": metadata["source_chars"],
                "length_group": metadata["length_group"],
                "base_status": base["status"],
                "sft_status": sft["status"],
                "base_source_extracted": base_extracted,
                "sft_source_extracted": sft_extracted,
                "base_test_stage_result": base_stage["test_stage_result"],
                "sft_test_stage_result": sft_stage["test_stage_result"],
                "base_noncached_task_or_summary": base_stage["noncached_task_or_summary"],
                "sft_noncached_task_or_summary": sft_stage["noncached_task_or_summary"],
                "base_stage_without_noncached_evidence": base_stage["stage_without_noncached_evidence"],
                "sft_stage_without_noncached_evidence": sft_stage["stage_without_noncached_evidence"],
                "base_all_commands_success": base_pass,
                "sft_all_commands_success": sft_pass,
                "base_command_records": base_stage["test_command_records"],
                "sft_command_records": sft_stage["test_command_records"],
                "base_no_tests_notice": base_stage["no_tests_notice"],
                "sft_no_tests_notice": sft_stage["no_tests_notice"],
                "paired_status": paired_status(base["status"], sft["status"]),
            }
        )

    n_targets = len(sample_rows)
    stage_summary: list[dict[str, Any]] = []
    command_diagnostics: dict[str, Any] = {}
    for condition in ("base", "sft_offline"):
        result_by_id = reports[condition]
        stage_by_id = stage_events[condition]
        extracted_ids = [sid for sid in result_by_id if extracted_source(result_by_id[sid])]
        test_ids = [sid for sid in result_by_id if stage_by_id[sid]["test_stage_result"]]
        noncached_ids = [sid for sid in result_by_id if stage_by_id[sid]["noncached_task_or_summary"]]
        no_noncached_ids = [sid for sid in result_by_id if stage_by_id[sid]["stage_without_noncached_evidence"]]
        success_ids = [sid for sid in result_by_id if all_commands_successful(result_by_id[sid])]
        no_test_ids = [sid for sid in extracted_ids if not stage_by_id[sid]["test_stage_result"]]
        extraction_errors = [sid for sid in result_by_id if not extracted_source(result_by_id[sid])]
        stage_summary.append(
            {
                "condition": condition,
                "target_samples": n_targets,
                "candidate_extracted": len(extracted_ids),
                "candidate_extracted_pct": round(100 * len(extracted_ids) / n_targets, 1),
                "test_task_result_samples": len(test_ids),
                "test_task_result_pct": round(100 * len(test_ids) / n_targets, 1),
                "noncached_task_or_summary_samples": len(noncached_ids),
                "stage_without_noncached_evidence_samples": len(no_noncached_ids),
                "all_commands_success": len(success_ids),
                "all_commands_success_pct": round(100 * len(success_ids) / n_targets, 1),
                "extracted_without_test_stage_result": len(no_test_ids),
                "source_extraction_errors": len(extraction_errors),
                "command_records": sum(len(result_by_id[sid].get("tests", [])) for sid in result_by_id),
                "command_records_with_no_tests_notice": sum(
                    1
                    for sid in result_by_id
                    for test in result_by_id[sid].get("tests", [])
                    if NO_TESTS_RE.search(test.get("output", "") or "")
                ),
                "positive_surefire_summaries": sum(
                    stage_by_id[sid]["surefire_positive_summaries"] for sid in result_by_id
                ),
                "gradle_test_task_records": sum(
                    stage_by_id[sid]["gradle_test_task_records"] for sid in result_by_id
                ),
                "gradle_cached_test_task_records": sum(
                    stage_by_id[sid]["gradle_cached_test_task_records"] for sid in result_by_id
                ),
                "gradle_up_to_date_test_task_records": sum(
                    stage_by_id[sid]["gradle_up_to_date_test_task_records"] for sid in result_by_id
                ),
                "gradle_noncached_test_task_records": sum(
                    stage_by_id[sid]["gradle_noncached_test_task_records"] for sid in result_by_id
                ),
            }
        )
        command_diagnostics[condition] = {
            "command_records": stage_summary[-1]["command_records"],
            "command_records_with_no_tests_notice": stage_summary[-1]["command_records_with_no_tests_notice"],
            "positive_surefire_summaries": stage_summary[-1]["positive_surefire_summaries"],
            "gradle_test_task_records": stage_summary[-1]["gradle_test_task_records"],
            "gradle_cached_test_task_records": stage_summary[-1]["gradle_cached_test_task_records"],
            "gradle_up_to_date_test_task_records": stage_summary[-1]["gradle_up_to_date_test_task_records"],
            "gradle_noncached_test_task_records": stage_summary[-1]["gradle_noncached_test_task_records"],
        }

    paired_stage_counter = Counter(
        (
            stage_events["base"][sample_id]["test_stage_result"],
            stage_events["sft_offline"][sample_id]["test_stage_result"],
        )
        for sample_id in reports["base"]
    )
    paired_stage_rows = [
        {
            "base_test_stage_result": base_flag,
            "sft_test_stage_result": sft_flag,
            "count": count,
        }
        for (base_flag, sft_flag), count in sorted(paired_stage_counter.items(), reverse=True)
    ]

    length_group_order = ["short", "medium", "long"]
    length_group_rows: list[dict[str, Any]] = []
    status_transition_counts: dict[str, Counter[str]] = {}
    for group in length_group_order:
        group_samples = [row for row in sample_rows if row["length_group"] == group]
        if not group_samples:
            continue
        base_pass = sum(row["base_all_commands_success"] for row in group_samples)
        sft_pass = sum(row["sft_all_commands_success"] for row in group_samples)
        status_counts = Counter(row["paired_status"] for row in group_samples)
        status_transition_counts[group] = status_counts
        lengths = [int(row["source_lines"]) for row in group_samples]
        domains = Counter(row["domain"] for row in group_samples)
        repos = Counter(row["repo"] for row in group_samples)
        length_group_rows.append(
            {
                "length_group": group,
                "observed_source_lines": f"{min(lengths)}-{max(lengths)}",
                "n_samples": len(group_samples),
                "base_pass_count": base_pass,
                "base_pass_rate_pct": round(100 * base_pass / len(group_samples), 1),
                "sft_pass_count": sft_pass,
                "sft_pass_rate_pct": round(100 * sft_pass / len(group_samples), 1),
                "delta_pp": round(100 * (sft_pass - base_pass) / len(group_samples), 1),
                "fail_to_pass": status_counts["fail_to_pass"],
                "fail_to_fail": status_counts["fail_to_fail"],
                "pass_to_fail": status_counts["pass_to_fail"],
                "pass_to_error": status_counts["pass_to_error"],
                "pass_to_pass": status_counts["pass_to_pass"],
                "domain_mix": "; ".join(f"{name}:{domains[name]}" for name in sorted(domains)),
                "repository_mix": "; ".join(f"{name}:{repos[name]}" for name in sorted(repos)),
            }
        )

    repo_accumulator: dict[str, dict[str, int]] = defaultdict(
        lambda: {"n": 0, "base_pass": 0, "sft_pass": 0}
    )
    repo_group_accumulator: dict[tuple[str, str], dict[str, int]] = defaultdict(
        lambda: {"n": 0, "base_pass": 0, "sft_pass": 0}
    )
    for row in sample_rows:
        repo = row["repo"]
        group = row["length_group"]
        for accumulator in (repo_accumulator[repo], repo_group_accumulator[(repo, group)]):
            accumulator["n"] += 1
            accumulator["base_pass"] += int(row["base_all_commands_success"])
            accumulator["sft_pass"] += int(row["sft_all_commands_success"])

    repository_rows: list[dict[str, Any]] = []
    for repo, values in sorted(repo_accumulator.items()):
        repository_rows.append(
            {
                "aggregation": "repository",
                "repo": repo,
                "n_repositories": 1,
                "n_files": values["n"],
                "base_pass_count": values["base_pass"],
                "base_pass_rate_pct": round(100 * values["base_pass"] / values["n"], 2),
                "sft_pass_count": values["sft_pass"],
                "sft_pass_rate_pct": round(100 * values["sft_pass"] / values["n"], 2),
                "delta_pp": round(100 * (values["sft_pass"] - values["base_pass"]) / values["n"], 2),
            }
        )
    n_repositories = len(repo_accumulator)
    base_micro = sum(value["base_pass"] for value in repo_accumulator.values()) / n_targets
    sft_micro = sum(value["sft_pass"] for value in repo_accumulator.values()) / n_targets
    base_macro = sum(value["base_pass"] / value["n"] for value in repo_accumulator.values()) / n_repositories
    sft_macro = sum(value["sft_pass"] / value["n"] for value in repo_accumulator.values()) / n_repositories
    repository_rows.extend(
        [
            {
                "aggregation": "file_micro",
                "repo": "All files",
                "n_repositories": n_repositories,
                "n_files": n_targets,
                "base_pass_count": sum(value["base_pass"] for value in repo_accumulator.values()),
                "base_pass_rate_pct": round(100 * base_micro, 2),
                "sft_pass_count": sum(value["sft_pass"] for value in repo_accumulator.values()),
                "sft_pass_rate_pct": round(100 * sft_micro, 2),
                "delta_pp": round(100 * (sft_micro - base_micro), 2),
            },
            {
                "aggregation": "repository_macro",
                "repo": "Equal-weight mean of repositories",
                "n_repositories": n_repositories,
                "n_files": n_targets,
                "base_pass_count": "",
                "base_pass_rate_pct": round(100 * base_macro, 2),
                "sft_pass_count": "",
                "sft_pass_rate_pct": round(100 * sft_macro, 2),
                "delta_pp": round(100 * (sft_macro - base_macro), 2),
            },
        ]
    )

    repo_length_rows: list[dict[str, Any]] = []
    for (repo, group), values in sorted(repo_group_accumulator.items()):
        repo_length_rows.append(
            {
                "repo": repo,
                "length_group": group,
                "n_samples": values["n"],
                "base_pass_count": values["base_pass"],
                "base_pass_rate_pct": round(100 * values["base_pass"] / values["n"], 1),
                "sft_pass_count": values["sft_pass"],
                "sft_pass_rate_pct": round(100 * values["sft_pass"] / values["n"], 1),
            }
        )

    stage_pair_counts = {
        "base_no_stage_to_sft_stage": paired_stage_counter[(False, True)],
        "base_stage_to_sft_no_stage": paired_stage_counter[(True, False)],
        "both_stage": paired_stage_counter[(True, True)],
        "neither_stage": paired_stage_counter[(False, False)],
    }
    total_status = {
        condition: dict(Counter(row["status"] for row in reports[condition].values()))
        for condition in RUNS
    }
    summary = {
        "scope": {
            "base_run": RUNS["base"],
            "sft_offline_run": RUNS["sft_offline"],
            "paired_samples": n_targets,
            "stage_unit": "sample-level; a test-stage result requires at least one captured Gradle :test task result (including FROM-CACHE or UP-TO-DATE) or a positive test summary",
            "noncached_evidence_unit": "sample-level; at least one Gradle :test task result without FROM-CACHE or UP-TO-DATE, or a positive Surefire summary; positive Core test summaries do not by themselves imply a noncached execution",
            "official_success_unit": "sample-level; report status passed and every deduplicated associated command returned exit code 0 without timeout",
        },
        "report_status_counts": total_status,
        "stage_summary": {row["condition"]: row for row in stage_summary},
        "paired_stage_transitions": stage_pair_counts,
        "command_output_diagnostics": command_diagnostics,
        "source_length_mapping": mapping_info,
        "source_length_group_summary": length_group_rows,
        "repository_rates": [row for row in repository_rows if row["aggregation"] == "repository"],
        "aggregation_comparison": {
            "n_repositories": n_repositories,
            "file_micro": {"base_rate_pct": round(100 * base_micro, 2), "sft_rate_pct": round(100 * sft_micro, 2), "delta_pp": round(100 * (sft_micro - base_micro), 2)},
            "repository_macro": {"base_rate_pct": round(100 * base_macro, 2), "sft_rate_pct": round(100 * sft_macro, 2), "delta_pp": round(100 * (sft_macro - base_macro), 2)},
        },
        "selected_business_case": {
            "id": "difficulty3_9a34df9fd4ed74fa",
            "repo": "opentcs",
            "class": "PeripheralAttachmentManager",
        },
        "test_stage_interpretation": {
            "noncached_evidence": "A Gradle :test task with no FROM-CACHE or UP-TO-DATE marker, or a positive Surefire summary; this is an output-record classification, not a count of JUnit methods or assertions.",
            "zero_test_guard": "A command-record count or a first-failure category is not sufficient evidence. Positive Surefire counts, Gradle test-task outcomes, or test-run summaries are required.",
        },
    }

    write_csv(ANALYSIS_DIR / "evaluation_stage_summary.csv", stage_summary)
    write_csv(ANALYSIS_DIR / "paired_stage_transitions.csv", paired_stage_rows)
    write_csv(ANALYSIS_DIR / "extended_sample_metrics.csv", sample_rows)
    write_csv(ANALYSIS_DIR / "source_length_group_summary.csv", length_group_rows)
    write_csv(ANALYSIS_DIR / "repository_rates_micro_macro.csv", repository_rows)
    write_csv(ANALYSIS_DIR / "source_length_repository_sensitivity.csv", repo_length_rows)
    (ANALYSIS_DIR / "extended_evaluation_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    build_stage_figure(stage_summary)
    build_length_figure(length_group_rows)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
