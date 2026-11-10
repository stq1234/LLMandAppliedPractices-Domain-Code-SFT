"""Check report totals against the archived result snapshots, without model execution."""
from collections import Counter, defaultdict
from pathlib import Path
import ast
import csv
import json
import math
import re
import statistics

ROOT = Path(__file__).resolve().parents[1]
summary = json.loads((ROOT / "analysis/report_data.json").read_text(encoding="utf-8"))
run_root = ROOT / "materials/evaluation/runs"
names = ["20261109-123917-aixcoder-v2", "20261109-124514-sft-offline"]
runs = [json.loads((run_root / name / "report.json").read_text(encoding="utf-8")) for name in names]
records = [{r["id"]: r for r in run["results"]} for run in runs]
assert len(records[0]) == len(records[1]) == 220
assert records[0].keys() == records[1].keys()
catalog = json.loads((ROOT / "materials/data_construction/scripts/remote/repository_catalog.json").read_text(encoding="utf-8"))
domains = {r["repository_full_name"]: r["domain"] for r in catalog}
counts = [defaultdict(Counter), defaultdict(Counter)]
transitions = Counter()
for key, left in records[0].items():
    right = records[1][key]
    assert left["ground_truth"] == right["ground_truth"]
    assert left["sample"] == right["sample"]
    assert left["task"] == right["task"] == "difficulty3"
    transitions[left["status"] + "->" + right["status"]] += 1
    for index, record in enumerate([left, right]):
        counts[index][domains[record["repo"]]][record["status"]] += 1
        if record["status"] == "passed":
            assert record["tests"]
            assert all(t["exit_code"] == 0 and not t["timed_out"] for t in record["tests"])
for row in summary["evaluation"]:
    for index, prefix in enumerate(["base", "sft"]):
        observed = counts[index][row["domain"]]
        assert sum(observed.values()) == row["n"]
        for expected, actual in [("pass", "passed"), ("fail", "failed"), ("error", "error")]:
            assert observed[actual] == row[prefix + "_" + expected]
assert transitions == Counter({"failed->passed": 131, "passed->failed": 3,
                               "passed->error": 1, "passed->passed": 38, "failed->failed": 47})
with (ROOT / "analysis/training_metrics.csv").open(encoding="utf-8-sig", newline="") as f:
    epochs = list(csv.DictReader(f))
with (ROOT / "analysis/training_step_metrics.csv").open(encoding="utf-8-sig", newline="") as f:
    steps = list(csv.DictReader(f))
log = (ROOT / "materials/training/7b_v2_sft_v4_0905.log").read_text(errors="replace")
eval_rows = [ast.literal_eval(v) for v in re.findall(r"\{[^{}\r\n]*\}", log) if "'eval_loss'" in v]
assert len(epochs) == len(eval_rows) == 10
train_steps = [r for r in steps if r["train_loss"]]
assert len(train_steps) == 2280
for row, logged in zip(epochs, eval_rows):
    epoch = int(row["epoch"])
    assert epoch == logged["epoch"]
    assert float(row["eval_loss"]) == logged["eval_loss"]
    group = [float(r["train_loss"]) for r in train_steps if (int(r["step"]) - 1) // 228 + 1 == epoch]
    assert len(group) == 228
    assert math.isclose(sum(group) / len(group), float(row["train_loss_mean"]), rel_tol=1e-12)
text = (ROOT / "实验报告.md").read_text(encoding="utf-8")
images = re.findall(r"!\[[^\]]*\]\(([^)]+)\)", text)
for path in images:
    assert (ROOT / path).is_file(), path
assert text.count("$$") % 2 == 0
assert not re.search(r"TODO|待补充|待补写|<!--|第\s*\d+\s*行代码|证据链|/nfsEDS/|/home/shaotianqi/", text)
assert len(images) == 12
figure_labels = re.findall(r"^\*\*图 (\d+)", text, re.M)
table_labels = re.findall(r"^\*\*表 ([A-Z]?\d+)", text, re.M)
assert figure_labels == [str(i) for i in range(1, 13)]
assert table_labels == [str(i) for i in range(1, 11)] + ["A1", "A2", "C1"]
assert not re.search(r"(?:图|表)\s+[RDET]\d+|### 7\.E", text)
for number in range(1, 17):
    assert re.search(r"^\[" + str(number) + r"\] ", text, re.M)
    assert re.search(r"\[\[?" + str(number) + r"\]\]?\(", text)
assert "过拟合" not in text.split("## 1 引言")[0]
assert "低代码" not in text
assert not re.search(r"(?<!\d)242(?!\d)|1,226|(?<!\d)1226(?!\d)|[九二]百[余多]|[92]00\s*[余多]", text)
assert "| 合计 | 1,204 | 984 | 220 |" in text
# Exact publication titles retain the publishers' spelling; prose uses display names.
prose = re.sub(r"## 参考文献\n.*?(?=## 附录 A)", "", text, flags=re.S)
assert not re.search(r"(?i)aixcoder[-_]|gpt-6-luna|sft-v4|\bepoch\b", prose)
assert "词元（token）" in prose
assert not (ROOT / "实验报告.html").exists()
assert not (ROOT / "output/pdf/实验报告.pdf").exists()
assert text.count('```') % 2 == 0
assert text.count('\\(') == text.count('\\)')
assert text.count('\\[') == text.count('\\]')
local_link_count = 0
for document in [ROOT / "实验报告.md", ROOT / "README.md",
                 ROOT / "materials/data_construction/README.md"]:
    content = document.read_text(encoding="utf-8")
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", content):
        if re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target) or target.startswith('#'):
            continue
        target = target.split('#', 1)[0]
        assert (document.parent / target).exists(), (document, target)
        local_link_count += 1
failure = json.loads((ROOT / "analysis/error_analysis_summary.json").read_text(encoding="utf-8"))
for label, total in [("base", 178), ("sft_offline", 51)]:
    category_counts = failure["by_run_and_domain"][label]["All domains"]["category_counts"]
    assert sum(category_counts.values()) == total
assert failure["scope"]["classified_nonpass_rows"] == 229
assert len(json.loads((ROOT / "analysis/case_study_revision.json").read_text(encoding="utf-8"))["cases"]) == 7
def csv_rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))

# Recompute new descriptive summaries from the archived source records.
java = [row for row in csv_rows(ROOT / "materials/training/data/v4_sample_index.csv")
        if row["language"].lower() == "java"]
java_by_id = {row["id"]: row for row in java}
source_index = csv_rows(ROOT / "materials/data_construction/analysis/sft_source_index.csv")
source_by_id = {row["id"]: row for row in source_index}
assert len(java) == len(java_by_id) == 1226
assert java_by_id.keys() == source_by_id.keys()
assert Counter(row["split"] for row in java) == {"train": 984, "test": 242}
for key, row in java_by_id.items():
    assert row["source_sha256_exact"] == source_by_id[key]["source_sha256"]
assert sum(row["only_sft_train"].lower() == "true" for row in java if row["split"] == "train") == 703
distribution = csv_rows(ROOT / "materials/data_construction/analysis/data_complexity_summary.csv")
assert len(distribution) == 18
functional_index = csv_rows(ROOT / "materials/data_construction/analysis/current_task3_source_index.csv")
functional_ids = {row["id"] for row in functional_index}
assert len(functional_index) == len(functional_ids) == 220
assert functional_ids <= java_by_id.keys()
reader_records = csv_rows(ROOT / "materials/data_construction/analysis/data_complexity_records.csv")
assert len(reader_records) == 1204
assert Counter(row["cohort"] for row in reader_records) == {"training": 984, "shared_functional_eval": 220}
assert {row["id"] for row in reader_records if row["cohort"] == "shared_functional_eval"} == functional_ids
assert {row["id"] for row in reader_records if row["cohort"] == "training"} == {row["id"] for row in java if row["split"] == "train"}
for row in distribution:
    assert row["cohort"] in {"training", "shared_functional_eval"}
    group = [record for record in java
             if (record["split"] == "train" if row["cohort"] == "training" else record["id"] in functional_ids)
             and source_by_id[record["id"]]["domain"] == row["domain"]]
    assert len(group) == int(row["count"])
    assert statistics.median(int(record[row["metric"]]) for record in group) == float(row["median"])
extended = csv_rows(ROOT / "analysis/extended_sample_metrics.csv")
assert len(extended) == 220
assert {row["id"] for row in extended} == records[0].keys()
for row in extended:
    key = row["id"]
    target = records[0][key]["sample"]["target_files"]
    assert len(target) == 1
    assert int(row["source_lines"]) == len(target[0]["ground_truth"].split('\n'))
    assert row["base_status"] == records[0][key]["status"]
    assert row["sft_status"] == records[1][key]["status"]
    assert row["v4_split"] == "test"
for label, total, base_pass, sft_pass in [("short", 75, 23, 67), ("medium", 72, 15, 61), ("long", 73, 4, 41)]:
    group = [row for row in extended if row["length_group"] == label]
    assert len(group) == total
    assert sum(row["base_status"] == "passed" for row in group) == base_pass
    assert sum(row["sft_status"] == "passed" for row in group) == sft_pass
extended_summary = json.loads((ROOT / "analysis/extended_evaluation_summary.json").read_text(encoding="utf-8"))
for index, label in enumerate(["base", "sft_offline"]):
    by_repository = defaultdict(list)
    for record in records[index].values():
        by_repository[record["repo"]].append(record)
    macro = statistics.mean(sum(record["status"] == "passed" for record in rows) / len(rows)
                            for rows in by_repository.values())
    expected = extended_summary["aggregation_comparison"]["repository_macro"]["base_rate_pct" if index == 0 else "sft_rate_pct"]
    assert round(100 * macro, 2) == expected
    assert len(by_repository) == 9
    assert sum(record["status"] == "passed" for record in by_repository["yudao-cloud"]) == index
gradient = [float(row["grad_norm"]) for row in train_steps]
assert round(statistics.median(gradient[:114]), 3) == 4.224
assert round(statistics.median(gradient[114:]), 3) == 0.902
assert round(statistics.median(gradient[-228:]), 4) == 0.0149
assert gradient.index(max(gradient)) + 1 == 546
assert round(max(gradient), 2) == 99.17
business_id = extended_summary["selected_business_case"]["id"]
business = [record[business_id] for record in records]
assert business[0]["status"] == "failed" and business[1]["status"] == "passed"
assert len(business[0]["tests"]) == len(business[1]["tests"]) == 4
assert all(test["exit_code"] != 0 for test in business[0]["tests"])
assert all(test["exit_code"] == 0 and not test["timed_out"] for test in business[1]["tests"])
assert len(business[0]["sample"]["target_files"][0]["ground_truth"].split('\n')) == 283
business_code = business[1]["model_output"]
for snippet in ['commAdapterRegistry.initialize();', 'peripheralEntryPool.initialize();',
                'autoAttachAllAdapters();', 'disableAndTerminateAdapter(entry);',
                'controllerPool.detachPeripheralController(entry.getLocation());',
                'commAdapter.initialize();', 'entry.setCommAdapterFactory(factory);',
                'entry.setCommAdapter(commAdapter);']:
    assert snippet in business_code
assert business_code.index('disableAndTerminateAdapter(entry);') < business_code.index('commAdapter.initialize();')
assert business_code.index('entry.setCommAdapter(commAdapter);') < business_code.index('new PeripheralAttachmentEvent(')
result = {"paired_samples": 220, "matching_samples_and_targets": True,
          "domain_and_status_totals_match": True, "pass_commands_succeeded": True,
          "training_epochs_checked": 10, "training_steps_checked": 2280,
          "validation_losses_match_training_log": True,
          "report_figure_count": len(images), "references_checked": 16,
          "report_table_count": len(table_labels), "figure_and_table_numbering_checked": True,
          "report_primary_format": "Markdown", "domain_file_synthesis_framing_checked": True,
          "human_readable_model_names_checked": True, "html_pdf_derivatives_removed": True,
          "local_document_links_checked": local_link_count, "markdown_delimiters_balanced": True,
          "nonpass_classifications_checked": 229, "evaluation_cases_checked": 7,
          "archived_java_source_records_checked": 1226, "java_complexity_records_checked": 1204,
          "reader_training_records": 984, "reader_shared_evaluation_samples": 220,
          "length_stratified_samples_checked": 220,
          "repository_macro_average_checked": True, "gradient_diagnostics_checked": True,
          "business_case_source_and_commands_checked": True,
          "transitions": dict(transitions), "report_images_found": images,
          "report_characters": len(text), "checks": "passed"}
(ROOT / "analysis/verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False))
