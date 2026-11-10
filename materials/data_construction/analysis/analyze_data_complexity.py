#!/usr/bin/env python3
"""Summarize archived Java v4 file/prompt/skeleton lengths for training and evaluation cohorts.

This script only reads the existing source index, v4 sample index, and the
adjacent data_statistics.json. It writes derived CSV/JSON summaries and a
three-panel PNG/SVG plot under this analysis directory.
"""
from __future__ import annotations

import csv
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

HERE = Path(__file__).resolve().parent
PACKAGE = HERE.parent
SOURCE_INDEX = HERE / "sft_source_index.csv"
FUNCTIONAL_EVAL_INDEX = HERE / "current_task3_source_index.csv"
V4_INDEX = PACKAGE.parent / "training" / "data" / "v4_sample_index.csv"
DATA_STATISTICS = HERE / "data_statistics.json"
RECORDS_CSV = HERE / "data_complexity_records.csv"
SUMMARY_CSV = HERE / "data_complexity_summary.csv"
SUMMARY_JSON = HERE / "data_complexity_statistics.json"
FIGURE_DIR = PACKAGE / "figures"
FIGURE_PNG = FIGURE_DIR / "data_complexity_distributions.png"
FIGURE_SVG = FIGURE_DIR / "data_complexity_distributions.svg"

DOMAIN_ORDER = ["企业信息化", "智慧物流", "智慧城市"]
COHORT_ORDER = ["training", "shared_functional_eval"]
COHORT_LABELS = {
    "training": "训练（n=984）",
    "shared_functional_eval": "评估（n=220）",
}
COHORT_COLORS = {
    "training": "#3B74A8",
    "shared_functional_eval": "#D98E32",
}
METRICS = {
    "source_lines": "目标源码行数",
    "prompt_chars": "提示字符数",
    "skeleton_chars": "目标骨架字符数",
}


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def parse_bool(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes"}


def quantile(sorted_values: list[int], q: float) -> float:
    """Linear-interpolated quantile, matching the common (n-1)*q convention."""
    if not sorted_values:
        raise ValueError("quantile of empty data")
    h = (len(sorted_values) - 1) * q
    lo = math.floor(h)
    hi = math.ceil(h)
    if lo == hi:
        return float(sorted_values[lo])
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (h - lo)


def main() -> None:
    source_rows = read_csv(SOURCE_INDEX)
    functional_eval_rows = read_csv(FUNCTIONAL_EVAL_INDEX)
    v4_rows = read_csv(V4_INDEX)
    with DATA_STATISTICS.open("r", encoding="utf-8") as handle:
        archived_stats = json.load(handle)
    source_stats = archived_stats["sft_source_set"]
    task3_stats = archived_stats["current_task3_samples"]

    functional_eval_by_id: dict[str, dict[str, str]] = {}
    for row in functional_eval_rows:
        sample_id = row["id"]
        if sample_id in functional_eval_by_id:
            raise ValueError(f"duplicate functional-evaluation id: {sample_id}")
        functional_eval_by_id[sample_id] = row
    if len(functional_eval_by_id) != 220:
        raise ValueError(f"expected 220 functional-evaluation records, found {len(functional_eval_by_id)}")

    source_by_id: dict[str, dict[str, str]] = {}
    for row in source_rows:
        sample_id = row["id"]
        if sample_id in source_by_id:
            raise ValueError(f"duplicate source-index id: {sample_id}")
        source_by_id[sample_id] = row

    joined: list[dict[str, object]] = []
    seen_v4_ids: set[str] = set()
    for row in v4_rows:
        if (row.get("language") or "").strip().strip('"').lower() != "java":
            continue
        sample_id = row["id"]
        if sample_id in seen_v4_ids:
            raise ValueError(f"duplicate Java v4 id: {sample_id}")
        seen_v4_ids.add(sample_id)
        source = source_by_id.get(sample_id)
        if source is None:
            raise ValueError(f"Java v4 id absent from source index: {sample_id}")
        if source["source_sha256"].strip().lower() != row["source_sha256_exact"].strip().lower():
            raise ValueError(f"exact source SHA-256 mismatch for {sample_id}")
        domain = source["domain"]
        if domain not in DOMAIN_ORDER:
            raise ValueError(f"unexpected domain for {sample_id}: {domain}")
        raw_split = row["split"].strip().lower()
        outer_role = {
            "train": "training",
            "eval": "shared_holdout_eval",
            "test": "shared_holdout_eval",
        }.get(raw_split)
        if outer_role is None:
            raise ValueError(f"unexpected v4 split for {sample_id}: {raw_split}")
        source_outer_role = {"train": "training", "eval": "shared_holdout_eval"}.get(
            source["sft_split"].strip().lower()
        )
        if source_outer_role != outer_role:
            raise ValueError(f"outer split mismatch for {sample_id}: {source['sft_split']} vs {raw_split}")
        only_sft_train = parse_bool(row["only_sft_train"])
        if only_sft_train != parse_bool(source["only_sft_train"]):
            raise ValueError(f"only_sft_train mismatch for {sample_id}")
        for field in ("source_chars", "source_lines", "prompt_chars", "skeleton_chars"):
            if int(row[field]) < 1:
                raise ValueError(f"nonpositive {field} for {sample_id}")
        joined.append({
            "id": sample_id,
            "domain": domain,
            "outer_role": outer_role,
            "is_functional_eval_valid": sample_id in functional_eval_by_id,
            "repository": source["repository"],
            "only_sft_train": only_sft_train,
            "source_path": source["source_path"],
            "source_sha256": row["source_sha256_exact"],
            "source_chars": int(row["source_chars"]),
            "source_lines": int(row["source_lines"]),
            "prompt_chars": int(row["prompt_chars"]),
            "skeleton_chars": int(row["skeleton_chars"]),
        })

    source_ids = set(source_by_id)
    if seen_v4_ids != source_ids:
        raise ValueError(
            f"Java/source ID set mismatch: v4-only={len(seen_v4_ids-source_ids)}, "
            f"source-only={len(source_ids-seen_v4_ids)}"
        )
    if len(joined) != 1226:
        raise ValueError(f"expected 1,226 joined Java records, found {len(joined)}")

    outer_test_ids = {
        str(row["id"]) for row in joined if row["outer_role"] == "shared_holdout_eval"
    }
    functional_eval_ids = set(functional_eval_by_id)
    if not functional_eval_ids.issubset(outer_test_ids):
        raise ValueError(f"functional-evaluation IDs outside shared holdout: {len(functional_eval_ids-outer_test_ids)}")
    excluded_ids = outer_test_ids - functional_eval_ids
    if len(outer_test_ids) != 242 or len(excluded_ids) != 22:
        raise ValueError(
            f"expected 242 shared holdout records and 22 excluded, found "
            f"{len(outer_test_ids)} and {len(excluded_ids)}"
        )

    counts = Counter((str(row["domain"]), str(row["outer_role"])) for row in joined)
    expected = {
        (item["domain"], role): int(item[raw_field])
        for item in source_stats["domain_counts"]
        for role, raw_field in (("training", "train"), ("shared_holdout_eval", "eval"))
    }
    if counts != expected:
        raise ValueError(f"domain/split counts differ from archived statistics: {counts} != {expected}")
    if Counter(str(row["outer_role"]) for row in joined) != {
        "training": 984, "shared_holdout_eval": 242
    }:
        raise ValueError("Java outer split does not match the archived 984/242 source set")
    functional_domain_counts = Counter(row["domain"] for row in functional_eval_by_id.values())
    expected_functional_domains = {
        item["domain"]: int(item["count"])
        for item in task3_stats["domain_counts"]
    }
    if functional_domain_counts != expected_functional_domains:
        raise ValueError(
            f"functional-evaluation domain counts differ from archived statistics: "
            f"{functional_domain_counts} != {expected_functional_domains}"
        )
    for sample_id, functional_row in functional_eval_by_id.items():
        v4 = next((row for row in joined if row["id"] == sample_id), None)
        if v4 is None or v4["outer_role"] != "shared_holdout_eval":
            raise ValueError(f"functional evaluation ID is not in the shared holdout: {sample_id}")
        if functional_row["source_sha256"].strip().lower() != str(v4["source_sha256"]).strip().lower():
            raise ValueError(f"functional-evaluation source hash differs for {sample_id}")

    analysis_rows: list[dict[str, object]] = []
    for row in joined:
        if row["outer_role"] == "training":
            row["cohort"] = "training"
            analysis_rows.append(row)
        elif row["is_functional_eval_valid"]:
            row["cohort"] = "shared_functional_eval"
            analysis_rows.append(row)
    if len(analysis_rows) != 1204:
        raise ValueError(f"expected 1,204 displayed records (984 + 220), found {len(analysis_rows)}")

    record_fields = [
        "id", "domain", "cohort", "repository", "only_sft_train", "source_path",
        "source_sha256", "source_chars", "source_lines", "prompt_chars", "skeleton_chars",
    ]
    with RECORDS_CSV.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=record_fields, extrasaction="ignore")
        writer.writeheader()
        for row in analysis_rows:
            writer.writerow(row)

    cohort_rows = {
        cohort: [row for row in analysis_rows if row["cohort"] == cohort]
        for cohort in COHORT_ORDER
    }
    grouped: dict[tuple[str, str, str], list[int]] = defaultdict(list)
    for cohort, cohort_records in cohort_rows.items():
        for row in cohort_records:
            for metric in METRICS:
                grouped[(str(row["domain"]), cohort, metric)].append(int(row[metric]))

    summary_rows: list[dict[str, object]] = []
    for domain in DOMAIN_ORDER:
        for cohort in COHORT_ORDER:
            for metric in METRICS:
                values = sorted(grouped[(domain, cohort, metric)])
                if not values:
                    raise ValueError(f"missing group: {domain}/{cohort}/{metric}")
                summary_rows.append({
                    "domain": domain,
                    "cohort": cohort,
                    "metric": metric,
                    "count": len(values),
                    "min": values[0],
                    "p25": round(quantile(values, 0.25), 3),
                    "median": round(quantile(values, 0.50), 3),
                    "p75": round(quantile(values, 0.75), 3),
                    "p90": round(quantile(values, 0.90), 3),
                    "max": values[-1],
                    "mean": round(sum(values) / len(values), 3),
                })
    with SUMMARY_CSV.open("w", encoding="utf-8-sig", newline="") as handle:
        fields = list(summary_rows[0])
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(summary_rows)

    domain_cohort_counts = {
        domain: {cohort: sum(1 for row in analysis_rows if row["domain"] == domain and row["cohort"] == cohort)
                 for cohort in COHORT_ORDER}
        for domain in DOMAIN_ORDER
    }
    definitions = {
        "source_lines": "Stored v4 index field; number of LF-separated segments in the target source string (equivalent to LF count + 1), including blank lines, comments, and a trailing empty segment when the string ends in LF; not nonblank LOC.",
        "source_chars": "Stored v4 index field for target source string character length; not a token count.",
        "prompt_chars": "Stored v4 index field for the rendered prompt string character length; not a token count.",
        "skeleton_chars": "Stored v4 index field for the target skeleton string character length; not a token count.",
        "quantiles": "Linear interpolation at (n-1)*q over ascending values; p25, median, p75, and p90.",
        "cohort_mapping": "Displayed rows consist only of the 984 training records and the 220 functionally evaluated records retained from the shared held-out source; record IDs and exact source hashes were matched.",
    }
    stats = {
        "source": {
            "displayed_record_count": len(analysis_rows),
            "source_index_id_join_verified": True,
            "source_sha256_join_verified": True,
            "cohort_counts": dict(Counter(str(row["cohort"]) for row in analysis_rows)),
            "domain_cohort_counts": domain_cohort_counts,
            "only_sft_train_counts_in_analysis": {
                cohort: dict(Counter(str(row["only_sft_train"]).lower() for row in cohort_rows[cohort]))
                for cohort in COHORT_ORDER
            },
            "functional_eval_ids_matched": len(functional_eval_by_id),
        },
        "definitions": definitions,
        "summaries": summary_rows,
        "files": {
            "records_csv": RECORDS_CSV.name,
            "summary_csv": SUMMARY_CSV.name,
            "figure_png": str(FIGURE_PNG.relative_to(PACKAGE)),
            "figure_svg": str(FIGURE_SVG.relative_to(PACKAGE)),
        },
    }
    with SUMMARY_JSON.open("w", encoding="utf-8") as handle:
        json.dump(stats, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    plot(analysis_rows)
    print(json.dumps(stats["source"], ensure_ascii=False, indent=2))
    for row in summary_rows:
        print(
            f"{row['domain']},{row['cohort']},{row['metric']},n={row['count']},"
            f"median={row['median']},p25={row['p25']},p75={row['p75']},"
            f"p90={row['p90']},min={row['min']},max={row['max']}"
        )


def plot(rows: list[dict[str, object]]) -> None:
    FIGURE_DIR.mkdir(parents=True, exist_ok=True)
    values: dict[tuple[str, str, str], list[int]] = defaultdict(list)
    for row in rows:
        for metric in METRICS:
            values[(str(row["domain"]), str(row["cohort"]), metric)].append(int(row[metric]))

    plt.rcParams.update({
        "font.family": ["Microsoft YaHei", "SimHei", "DejaVu Sans"],
        "font.size": 10,
        "axes.titlesize": 12,
        "axes.labelsize": 10,
        "svg.fonttype": "none",
    })
    fig, axes = plt.subplots(1, 3, figsize=(15.2, 5.8))
    # Reserve separate vertical bands for the overall title, shared legend,
    # and axes (including their individual titles) to avoid crowding.
    fig.subplots_adjust(left=0.06, right=0.99, bottom=0.15, top=0.77, wspace=0.25)
    centers = [1.0, 3.2, 5.4]
    offsets = {"training": -0.23, "shared_functional_eval": 0.23}
    domain_labels = {"企业信息化": "企业信息化", "智慧物流": "智慧物流", "智慧城市": "智慧城市"}

    for ax, (metric, title) in zip(axes, METRICS.items()):
        series: list[list[int]] = []
        positions: list[float] = []
        colors: list[str] = []
        for center, domain in zip(centers, DOMAIN_ORDER):
            for cohort in COHORT_ORDER:
                series.append(values[(domain, cohort, metric)])
                positions.append(center + offsets[cohort])
                colors.append(COHORT_COLORS[cohort])
        boxes = ax.boxplot(
            series, positions=positions, widths=0.34, patch_artist=True,
            showfliers=False, whis=(5, 95), manage_ticks=False,
            medianprops={"color": "#202020", "linewidth": 1.4},
            whiskerprops={"color": "#4b4b4b", "linewidth": 0.9},
            capprops={"color": "#4b4b4b", "linewidth": 0.9},
        )
        for box, color in zip(boxes["boxes"], colors):
            box.set_facecolor(color)
            box.set_alpha(0.78)
            box.set_edgecolor("#333333")
        ax.set_yscale("log")
        ax.set_xticks(centers, [domain_labels[d] for d in DOMAIN_ORDER])
        ax.set_title(title)
        ax.set_ylabel("字符数" if metric != "source_lines" else "LF分段数（行数代理）")
        ax.grid(axis="y", which="both", color="#d8d8d8", linewidth=0.65, alpha=0.75)
        ax.set_axisbelow(True)
        ax.tick_params(axis="x", labelrotation=0, pad=6)

    fig.suptitle("Java文件级数据长度分布（对数纵轴）", fontsize=14, y=0.985)
    fig.legend(
        handles=[Patch(facecolor=COHORT_COLORS[cohort], label=COHORT_LABELS[cohort])
                 for cohort in COHORT_ORDER],
        loc="upper center", bbox_to_anchor=(0.5, 0.905), ncol=2, frameon=False,
    )
    fig.text(
        0.5, 0.035,
        "箱体为四分位距，中线为中位数，须线为第5–95百分位；分布按领域及最终功能实验保留样本统计。",
        ha="center", va="top", fontsize=9,
    )
    fig.savefig(FIGURE_PNG, dpi=220, bbox_inches="tight")
    fig.savefig(FIGURE_SVG, bbox_inches="tight")
    plt.close(fig)


if __name__ == "__main__":
    main()
