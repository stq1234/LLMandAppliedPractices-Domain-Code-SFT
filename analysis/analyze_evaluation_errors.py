from __future__ import annotations

"""Classify sample-level failures in the paired full-file evaluation runs.

Reads only archived evaluation artifacts in materials/evaluation/runs and writes
reproducible sample labels and aggregate tables to this analysis directory.
"""

import csv
import json
import re
from collections import Counter
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
ANALYSIS_DIR = Path(__file__).resolve().parent
RUNS_DIR = PROJECT_ROOT / "materials" / "evaluation" / "runs"
RUNS = {
    "base": "20261109-123917-aixcoder-v2",
    "sft_offline": "20261109-124514-sft-offline",
}
DOMAINS = ("企业信息化", "智慧物流", "智慧城市", "All domains")
CATEGORIES = (
    "compile_type_or_api",
    "compile_missing_symbol",
    "compile_syntax_or_structure",
    "compile_other",
    "assertion_or_expected_result",
    "runtime_exception",
    "runtime_timeout",
    "build_or_test_infrastructure",
    "output_extraction_error",
    "other_unclear",
)

ASSERTION_RE = re.compile(
    r"AssertionFailedError|AssertionError|expected\s*[:<].{0,180}but was|"
    r"Tests run:\s*\d+\s*,\s*Failures:\s*[1-9]\d*",
    re.IGNORECASE,
)
TEST_ERROR_RE = re.compile(
    r"Tests run:\s*\d+\s*,\s*Failures:\s*\d+\s*,\s*Errors:\s*[1-9]\d*"
    r"|java\.lang\.[\w.$]*(?:Exception|Error)\s+at\s+[^\n]*Test\w*\.java:\d+"
    r"|Exception in thread\s+\"[^\"]+\"",
    re.IGNORECASE,
)
COMPILE_RE = re.compile(
    r"Compilation failure|Compilation failed|maven-compiler-plugin.{0,100}compile|"
    r"error:\s|javac.{0,60}error|warnings found and -Werror specified",
    re.IGNORECASE,
)
SYNTAX_RE = re.compile(
    r"class, interface, (?:or )?enum expected|class, interface, enum, or record expected|"
    r"reached end of file while parsing|unclosed (?:comment|string literal)|"
    r"illegal start of|not a statement|';' expected|<identifier> expected|"
    r"enum constant expected|missing method body|file does not contain class|"
    r"bad source file|duplicate class|already defined in|duplicate variable|"
    r"might already have been assigned|"
    r"finally' without 'try|finally without try|unexpected end|parse error",
    re.IGNORECASE,
)
MISSING_SYMBOL_RE = re.compile(
    r"cannot find symbol|symbol:\s*(?:class|method|variable)|"
    r"package [\w.]+ does not exist|cannot find class|file does not contain class",
    re.IGNORECASE,
)
TYPE_API_RE = re.compile(
    r"incompatible types|possible lossy conversion|bad operand types|"
    r"no suitable method|method .{0,120} cannot be applied|cannot be converted|"
    r"cannot assign a value to final|cannot be referenced from a static context|"
    r"does not override or implement|not a functional interface|"
    r"constructor .{0,160} cannot be applied|actual and formal argument lists differ|"
    r"Invalid SafeVarargs annotation|not a varargs method|"
    r"unreported exception|ambiguous|might not have been initialized|"
    r"type argument .{0,100} not within bounds|"
    r"inference variable .{0,100} has incompatible bounds",
    re.IGNORECASE,
)
INFRA_RE = re.compile(
    r"Could not resolve|Could not transfer|UnknownHostException|Connection timed out|"
    r"No space left on device|Permission denied|Could not find artifact|"
    r"failed to download|repository .* unavailable|daemon disappeared",
    re.IGNORECASE,
)


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def read_csv(path: Path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_csv(path: Path, rows, fields=None):
    if fields is None:
        fields = list(rows[0]) if rows else []
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def result_logs(result):
    """Only failed command outputs; successful repeated dependency logs are ignored."""
    logs = []
    for index, test in enumerate(result.get("tests", [])):
        if test.get("timed_out") or test.get("exit_code") != 0:
            logs.append({
                "index": index,
                "timed_out": bool(test.get("timed_out")),
                "exit_code": test.get("exit_code"),
                "command": test.get("command", ""),
                "output": test.get("output", "") or "",
            })
    return logs


def informative_lines(text):
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if (
            "[error]" in stripped.lower()
            or "warning:" in stripped.lower()
            or "error:" in stripped.lower()
            or "symbol:" in stripped.lower()
            or "bad source file:" in stripped.lower()
            or "file does not contain class" in stripped.lower()
            or ASSERTION_RE.search(stripped)
            or TEST_ERROR_RE.search(stripped)
            or INFRA_RE.search(stripped)
        ):
            lines.append(stripped)
    return lines


def best_diagnostic(lines):
    specific = [
        line for line in lines
        if re.search(
            r"error:\s|cannot find symbol|symbol:|Assertion(?:Failed)?Error|"
            r"expected\s*[:<]|but was|Exception at|Exception in thread|"
            r"bad source file|file does not contain class|timed out|"
            r"warnings found and -Werror|warning:",
            line,
            re.IGNORECASE,
        )
        and not re.search(r"Failed to execute goal|BUILD FAILED|BUILD FAILURE|\[Help \d+\]", line, re.I)
    ]
    return (specific or lines or [""])[0]


def sanitize_evidence(line: str) -> str:
    line = re.sub(r"/home/[^\s]*/repo/", "repo/", line)
    line = re.sub(r"/home/[^\s]*/java-evaluator/build/tmp/[^\s]*/repo/", "repo/", line)
    return line[:500]


def classify(result):
    status = result.get("status", "other_unclear")
    raw_error = str(result.get("error", "") or "")
    failed_logs = result_logs(result)
    outputs = [log["output"] for log in failed_logs]
    combined = "\n".join(outputs)
    target_paths = [
        item.get("source_path", "")
        for item in result.get("sample", {}).get("target_files", [])
    ]
    target_lines = []
    for path in target_paths:
        if not path:
            continue
        target_lines.extend(
            line for line in informative_lines(combined) if path.replace("\\", "/") in line
        )

    if status == "error":
        if re.search(r"Model did not return a complete Java source file|complete Java source", raw_error, re.I):
            return {
                "category": "output_extraction_error",
                "confidence": "high",
                "evidence_source": "run_error",
                "evidence": sanitize_evidence(raw_error),
                "failed_command_count": len(failed_logs),
                "failed_command_indexes": ";".join(str(row["index"]) for row in failed_logs),
                "target_diagnostic_count": len(target_lines),
            }
        if failed_logs and any(log["timed_out"] for log in failed_logs):
            category, source = "runtime_timeout", "test_output"
        else:
            category, source = "other_unclear", "run_error"
        return {
            "category": category,
            "confidence": "medium" if category == "runtime_timeout" else "low",
            "evidence_source": source,
            "evidence": sanitize_evidence(raw_error or combined),
            "failed_command_count": len(failed_logs),
            "failed_command_indexes": ";".join(str(row["index"]) for row in failed_logs),
            "target_diagnostic_count": len(target_lines),
        }

    # A test assertion or thrown test exception proves compilation reached the
    # test phase. Prefer that evidence over generic/duplicated Maven compile text.
    assertion_lines = [line for line in informative_lines(combined) if ASSERTION_RE.search(line)]
    if assertion_lines:
        category, source, evidence = (
            "assertion_or_expected_result", "test_output", assertion_lines[0]
        )
    else:
        runtime_lines = [line for line in informative_lines(combined) if TEST_ERROR_RE.search(line)]
        timeout_logs = [log for log in failed_logs if log["timed_out"]]
        if runtime_lines:
            category, source, evidence = "runtime_exception", "test_output", runtime_lines[0]
        elif timeout_logs:
            category, source, evidence = "runtime_timeout", "test_output", "test command timed out"
        else:
            # Prefer diagnostics pointing to this sample's generated file. If
            # none are present, the first useful compiler diagnostic is still
            # retained, but labeled as lower-confidence when it points elsewhere.
            diag_text = "\n".join(target_lines) if target_lines else combined
            diagnostic_lines = informative_lines(diag_text)
            if INFRA_RE.search(combined) and not target_lines:
                category = "build_or_test_infrastructure"
            elif SYNTAX_RE.search(diag_text):
                category = "compile_syntax_or_structure"
            elif MISSING_SYMBOL_RE.search(diag_text):
                category = "compile_missing_symbol"
            elif TYPE_API_RE.search(diag_text):
                category = "compile_type_or_api"
            elif COMPILE_RE.search(combined):
                category = "compile_other"
            else:
                category = "other_unclear"
            source = "target_compiler_diagnostic" if target_lines else "project_compiler_diagnostic"
            evidence = best_diagnostic(diagnostic_lines) if diagnostic_lines else next(
                (line.strip() for line in combined.splitlines() if line.strip()), ""
            )

    confidence = "high"
    if category in ("compile_type_or_api", "compile_missing_symbol", "compile_syntax_or_structure"):
        if not target_lines:
            confidence = "medium"
            source = "project_compiler_diagnostic"
    elif category == "compile_other":
        confidence = "medium" if target_lines else "low"
    elif category in ("other_unclear", "build_or_test_infrastructure"):
        confidence = "low" if category == "other_unclear" else "medium"
    return {
        "category": category,
        "confidence": confidence,
        "evidence_source": source,
        "evidence": sanitize_evidence(evidence),
        "failed_command_count": len(failed_logs),
        "failed_command_indexes": ";".join(str(row["index"]) for row in failed_logs),
        "target_diagnostic_count": len(target_lines),
    }


def pair_outcome(base_status, sft_status):
    if base_status == "failed" and sft_status == "passed":
        return "repaired_fail_to_pass"
    if base_status == "failed" and sft_status == "failed":
        return "residual_fail_to_fail"
    if base_status == "passed" and sft_status == "failed":
        return "new_failure_pass_to_fail"
    if base_status == "passed" and sft_status == "error":
        return "new_error_pass_to_error"
    if base_status == "passed" and sft_status == "passed":
        return "stable_pass"
    return f"other_{base_status}_to_{sft_status}"


def main():
    sample_index = {
        row["id"]: row
        for row in read_csv(ANALYSIS_DIR / "evaluation_sample_index.csv")
    }
    by_run = {}
    classified = {}
    for condition, name in RUNS.items():
        report = read_json(RUNS_DIR / name / "report.json")
        results = {row["id"]: row for row in report["results"]}
        if len(results) != 220:
            raise ValueError(f"Expected 220 unique results for {condition}, got {len(results)}")
        by_run[condition] = results
        classified[condition] = {sample_id: classify(row) for sample_id, row in results.items()}

    if set(by_run["base"]) != set(by_run["sft_offline"]):
        raise ValueError("Base and offline SFT sample IDs differ")

    classification_rows = []
    paired_rows = []
    for sample_id in sorted(by_run["base"]):
        base = by_run["base"][sample_id]
        sft = by_run["sft_offline"][sample_id]
        meta = sample_index[sample_id]
        outcome = pair_outcome(base["status"], sft["status"])
        for condition, row in (("base", base), ("sft_offline", sft)):
            if row["status"] == "passed":
                continue
            details = classified[condition][sample_id]
            classification_rows.append({
                "condition": condition,
                "run": RUNS[condition],
                "id": sample_id,
                "domain": meta["domain"],
                "repo": row["repo"],
                "status": row["status"],
                "failure_category": details["category"],
                "confidence": details["confidence"],
                "pair_outcome": outcome,
                "target_path": meta["target_paths"],
                "failed_command_count": details["failed_command_count"],
                "failed_command_indexes": details["failed_command_indexes"],
                "target_diagnostic_count": details["target_diagnostic_count"],
                "evidence_source": details["evidence_source"],
                "evidence_excerpt": details["evidence"],
            })
        paired_rows.append({
            "id": sample_id,
            "domain": meta["domain"],
            "repo": base["repo"],
            "base_status": base["status"],
            "base_failure_category": classified["base"][sample_id]["category"]
                if base["status"] != "passed" else "passed",
            "sft_status": sft["status"],
            "sft_failure_category": classified["sft_offline"][sample_id]["category"]
                if sft["status"] != "passed" else "passed",
            "paired_outcome": outcome,
        })

    write_csv(ANALYSIS_DIR / "error_classification.csv", classification_rows)
    write_csv(ANALYSIS_DIR / "paired_sample_failure_transitions.csv", paired_rows)

    by_domain_category = []
    run_counts = {}
    for condition in RUNS:
        run_counts[condition] = {}
        for domain in DOMAINS:
            ids = [
                sample_id for sample_id, row in by_run[condition].items()
                if domain == "All domains" or sample_index[sample_id]["domain"] == domain
            ]
            failures = [sample_id for sample_id in ids if by_run[condition][sample_id]["status"] != "passed"]
            counts = Counter(classified[condition][sample_id]["category"] for sample_id in failures)
            run_counts[condition][domain] = {
                "n_samples": len(ids),
                "nonpass_samples": len(failures),
                "category_counts": {category: counts.get(category, 0) for category in CATEGORIES},
            }
            for category in CATEGORIES:
                value = counts.get(category, 0)
                by_domain_category.append({
                    "condition": condition,
                    "run": RUNS[condition],
                    "domain": domain,
                    "n_samples": len(ids),
                    "nonpass_samples": len(failures),
                    "failure_category": category,
                    "count": value,
                    "share_of_nonpass": f"{value / len(failures):.6f}" if failures else "",
                    "share_of_all_samples": f"{value / len(ids):.6f}" if ids else "",
                })
    write_csv(ANALYSIS_DIR / "failure_modes_by_domain.csv", by_domain_category)

    outcome_names = (
        "repaired_fail_to_pass",
        "residual_fail_to_fail",
        "new_failure_pass_to_fail",
        "new_error_pass_to_error",
        "stable_pass",
    )
    outcome_rows = []
    for domain in DOMAINS:
        ids = [
            row["id"] for row in paired_rows
            if domain == "All domains" or row["domain"] == domain
        ]
        count = Counter(row["paired_outcome"] for row in paired_rows if row["id"] in ids)
        for outcome in outcome_names:
            outcome_rows.append({
                "domain": domain,
                "n_paired_samples": len(ids),
                "paired_outcome": outcome,
                "count": count.get(outcome, 0),
            })
    write_csv(ANALYSIS_DIR / "paired_failure_outcomes.csv", outcome_rows)

    mode_transition_counts = Counter(
        (row["domain"], row["paired_outcome"], row["base_failure_category"], row["sft_failure_category"])
        for row in paired_rows
    )
    mode_transition_rows = []
    for (domain, outcome, base_category, sft_category), count in sorted(mode_transition_counts.items()):
        if outcome == "stable_pass":
            continue
        mode_transition_rows.append({
            "domain": domain,
            "paired_outcome": outcome,
            "base_failure_category": base_category,
            "sft_failure_category": sft_category,
            "count": count,
        })
    write_csv(ANALYSIS_DIR / "paired_failure_mode_transitions.csv", mode_transition_rows)

    overall_transitions = Counter(row["paired_outcome"] for row in paired_rows)
    total_classified = len(classification_rows)
    summary = {
        "scope": {
            "runs": RUNS,
            "paired_samples": len(paired_rows),
            "classified_nonpass_rows": total_classified,
            "domains": list(DOMAINS),
            "unit_of_count": "one row per sample per run; repeated commands/log lines are not repeated sample counts",
        },
        "classification_rules_file": "error_classification_rules.md",
        "categories": list(CATEGORIES),
        "by_run_and_domain": run_counts,
        "paired_outcomes_all_domains": {name: overall_transitions.get(name, 0) for name in outcome_names},
        "confidence_counts": dict(Counter(row["confidence"] for row in classification_rows)),
        "low_confidence_or_unclear_ids": [
            {"condition": row["condition"], "id": row["id"], "category": row["failure_category"], "evidence": row["evidence_excerpt"]}
            for row in classification_rows
            if row["confidence"] == "low" or row["failure_category"] == "other_unclear"
        ],
    }
    (ANALYSIS_DIR / "error_analysis_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({
        "classification_rows": len(classification_rows),
        "paired_rows": len(paired_rows),
        "by_run_overall": {
            run: run_counts[run]["All domains"] for run in RUNS
        },
        "paired_outcomes": summary["paired_outcomes_all_domains"],
        "low_confidence_or_unclear": summary["low_confidence_or_unclear_ids"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
