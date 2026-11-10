from __future__ import annotations

"""Render sample-level failure, paired-outcome, and repository-rate figures."""

import csv
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


ANALYSIS_DIR = Path(__file__).resolve().parent
FIGURE_DIR = ANALYSIS_DIR / "figures"
DOMAINS = ("企业信息化", "智慧物流", "智慧城市")
CONDITIONS = ("base", "sft_offline")
CONDITION_LABELS = {"base": "基础组", "sft_offline": "SFT 离线预测"}
FAILURE_CATEGORIES = (
    "compile_syntax_or_structure",
    "compile_missing_symbol",
    "compile_type_or_api",
    "compile_other",
    "assertion_or_expected_result",
    "runtime_exception",
    "runtime_timeout",
    "build_or_test_infrastructure",
    "output_extraction_error",
    "other_unclear",
)
FAILURE_LABELS = {
    "compile_syntax_or_structure": "编译：语法/结构",
    "compile_missing_symbol": "编译：缺少符号",
    "compile_type_or_api": "编译：类型/API",
    "compile_other": "编译：其他",
    "assertion_or_expected_result": "测试：断言/预期值",
    "runtime_exception": "测试：运行异常",
    "runtime_timeout": "测试超时",
    "build_or_test_infrastructure": "构建/测试环境",
    "output_extraction_error": "输出提取错误",
    "other_unclear": "原因不清",
}
FAILURE_COLORS = {
    "compile_syntax_or_structure": "#4C78A8",
    "compile_missing_symbol": "#72B7B2",
    "compile_type_or_api": "#F2CF5B",
    "compile_other": "#B279A2",
    "assertion_or_expected_result": "#E45756",
    "runtime_exception": "#FF9D98",
    "runtime_timeout": "#9D755D",
    "build_or_test_infrastructure": "#BAB0AC",
    "output_extraction_error": "#D37295",
    "other_unclear": "#79706E",
}
OUTCOMES = (
    "repaired_fail_to_pass",
    "residual_fail_to_fail",
    "new_failure_pass_to_fail",
    "new_error_pass_to_error",
    "stable_pass",
)
OUTCOME_LABELS = {
    "repaired_fail_to_pass": "失败→通过",
    "residual_fail_to_fail": "失败→失败",
    "new_failure_pass_to_fail": "通过→失败",
    "new_error_pass_to_error": "通过→错误",
    "stable_pass": "通过→通过",
}
OUTCOME_COLORS = {
    "repaired_fail_to_pass": "#59A14F",
    "residual_fail_to_fail": "#4C78A8",
    "new_failure_pass_to_fail": "#E15759",
    "new_error_pass_to_error": "#B07AA1",
    "stable_pass": "#BAB0AC",
}


def read_csv(path: Path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def configure_style():
    plt.rcParams.update({
        "font.family": "Microsoft YaHei",
        "axes.unicode_minus": False,
        "font.size": 10,
        "axes.titlesize": 14,
        "axes.labelsize": 11,
        "legend.fontsize": 9,
        "figure.dpi": 150,
        "savefig.dpi": 300,
        "svg.fonttype": "none",
    })


def save_figure(fig, stem: str):
    FIGURE_DIR.mkdir(parents=True, exist_ok=True)
    fig.savefig(FIGURE_DIR / f"{stem}.png", dpi=300, bbox_inches="tight")
    fig.savefig(FIGURE_DIR / f"{stem}.svg", bbox_inches="tight")
    plt.close(fig)


def plot_failure_distribution():
    rows = read_csv(ANALYSIS_DIR / "failure_modes_by_domain.csv")
    counts = {
        (row["condition"], row["failure_category"]): int(row["count"])
        for row in rows
        if row["domain"] == "All domains"
    }
    fig, ax = plt.subplots(figsize=(12.6, 5.0), constrained_layout=True)
    y_positions = [1, 0]
    conditions = ("base", "sft_offline")
    for y, condition in zip(y_positions, conditions):
        left = 0
        for category in FAILURE_CATEGORIES:
            value = counts.get((condition, category), 0)
            if not value:
                continue
            ax.barh(
                y, value, left=left, height=0.48,
                color=FAILURE_COLORS[category], edgecolor="white", linewidth=0.8,
                label=FAILURE_LABELS[category],
            )
            if value >= 8:
                ax.text(left + value / 2, y, str(value), ha="center", va="center",
                        color="white" if category in ("compile_syntax_or_structure", "compile_missing_symbol") else "#222222",
                        fontsize=9, fontweight="bold")
            left += value
        ax.text(left + 3, y, f"{left} 个非通过样本", ha="left", va="center", fontsize=10)
    ax.set_yticks(y_positions, ["基础组", "SFT 离线预测"])
    ax.set_xlim(0, 220)
    ax.set_xticks(range(0, 221, 20))
    ax.set_xlabel("非通过样本数（每组分母 n=220；每个样本只计一次）")
    ax.set_title("全文件生成的首要失败类型")
    ax.grid(axis="x", color="#D9D9D9", linewidth=0.7)
    ax.set_axisbelow(True)
    ax.spines[["top", "right", "left"]].set_visible(False)
    handles, labels = ax.get_legend_handles_labels()
    unique = dict(zip(labels, handles))
    ax.legend(unique.values(), unique.keys(), loc="upper center", bbox_to_anchor=(0.5, -0.18),
              ncol=4, frameon=False)
    save_figure(fig, "error_categories_by_condition")


def plot_paired_transitions():
    rows = read_csv(ANALYSIS_DIR / "paired_failure_outcomes.csv")
    counts = {
        (row["domain"], row["paired_outcome"]): int(row["count"])
        for row in rows
    }
    domain_ns = {
        row["domain"]: int(row["n_paired_samples"])
        for row in rows
        if row["paired_outcome"] == "repaired_fail_to_pass"
    }
    plot_domains = (*DOMAINS, "All domains")
    labels = [f"{domain}（n={domain_ns[domain]}）" for domain in plot_domains]
    fig, ax = plt.subplots(figsize=(11.8, 5.3), constrained_layout=True)
    y_positions = list(range(len(plot_domains)))[::-1]
    for y, domain in zip(y_positions, plot_domains):
        left = 0
        for outcome in OUTCOMES:
            value = counts.get((domain, outcome), 0)
            if not value:
                continue
            ax.barh(
                y, value, left=left, height=0.54,
                color=OUTCOME_COLORS[outcome], edgecolor="white", linewidth=0.8,
                label=OUTCOME_LABELS[outcome],
            )
            if value >= 7:
                ax.text(left + value / 2, y, str(value), ha="center", va="center",
                        color="white" if outcome in ("repaired_fail_to_pass", "residual_fail_to_fail") else "#222222",
                        fontsize=9, fontweight="bold")
            left += value
        ax.text(left + 2, y, str(left), ha="left", va="center", fontsize=9)
    ax.set_yticks(y_positions, labels)
    ax.set_xlim(0, 235)
    ax.set_xticks(range(0, 221, 20))
    ax.set_xlabel("配对样本数（每个样本只计一次）")
    ax.set_title("按领域统计的基础组→SFT 离线预测状态迁移")
    ax.grid(axis="x", color="#D9D9D9", linewidth=0.7)
    ax.set_axisbelow(True)
    ax.spines[["top", "right", "left"]].set_visible(False)
    handles, legend_labels = ax.get_legend_handles_labels()
    unique = dict(zip(legend_labels, handles))
    ax.legend(unique.values(), unique.keys(), loc="upper center", bbox_to_anchor=(0.5, -0.18),
              ncol=3, frameon=False)
    save_figure(fig, "paired_outcomes_by_domain")


def plot_repository_pass_rates():
    rows = read_csv(ANALYSIS_DIR / "paired_sample_failure_transitions.csv")
    grouped = defaultdict(list)
    for row in rows:
        grouped[row["repo"]].append(row)
    repo_rows = []
    for repo, sample_rows in grouped.items():
        total = len(sample_rows)
        base_pass = sum(row["base_status"] == "passed" for row in sample_rows)
        sft_pass = sum(row["sft_status"] == "passed" for row in sample_rows)
        repo_rows.append({
            "repo": repo,
            "n": total,
            "base_pass": base_pass,
            "sft_pass": sft_pass,
            "base_rate": base_pass / total,
            "sft_rate": sft_pass / total,
        })
    repo_rows.sort(key=lambda row: (row["sft_rate"], row["base_rate"], row["repo"]))

    fig, ax = plt.subplots(figsize=(11.8, 6.4), constrained_layout=True)
    y_positions = list(range(len(repo_rows)))
    offsets = {"base": -0.09, "sft_offline": 0.09}
    for index, row in enumerate(repo_rows):
        base_x = 100 * row["base_rate"]
        sft_x = 100 * row["sft_rate"]
        ax.plot([base_x, sft_x], [index, index], color="#A6A6A6", linewidth=1.4, zorder=1)
        ax.scatter(base_x, index + offsets["base"], color="#4C78A8", s=44, zorder=3,
                   label="基础组" if index == 0 else None)
        ax.scatter(sft_x, index + offsets["sft_offline"], color="#E15759", s=44, zorder=3,
                   label="SFT 离线预测" if index == 0 else None)
        ax.text(base_x, index - 0.22, f"{row['base_pass']}/{row['n']}", ha="center", va="top",
                color="#315A80", fontsize=8)
        ax.text(sft_x, index + 0.22, f"{row['sft_pass']}/{row['n']}", ha="center", va="bottom",
                color="#A93638", fontsize=8)
    ax.set_yticks(y_positions, [row["repo"] for row in repo_rows])
    ax.set_xlim(0, 100)
    ax.set_xticks(range(0, 101, 10))
    ax.set_xlabel("通过样本 / 仓库样本数（%）")
    ax.set_title("按仓库比较文件级通过率（点旁为通过数/仓库样本数）")
    ax.grid(axis="x", color="#D9D9D9", linewidth=0.7)
    ax.set_axisbelow(True)
    ax.spines[["top", "right", "left"]].set_visible(False)
    ax.legend(loc="lower right", frameon=False, ncol=2)
    save_figure(fig, "repository_pass_rates")


def main():
    configure_style()
    plot_failure_distribution()
    plot_paired_transitions()
    plot_repository_pass_rates()
    print(f"Wrote 3 PNG+SVG figures to {FIGURE_DIR}")


if __name__ == "__main__":
    main()
