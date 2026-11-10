#!/usr/bin/env python3
"""Render the Java data-construction flow and source-set split charts."""

from __future__ import annotations

import json
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch


ROOT = Path(__file__).resolve().parents[1]
STATISTICS = ROOT / "analysis" / "data_statistics.json"
OUTPUT = ROOT / "figures"


def configure_fonts() -> None:
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "DejaVu Sans"]
    plt.rcParams["axes.unicode_minus"] = False
    plt.rcParams["svg.fonttype"] = "none"


def save_figure(fig: plt.Figure, stem: str) -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUTPUT / f"{stem}.png", dpi=180, bbox_inches="tight", facecolor="white")
    fig.savefig(OUTPUT / f"{stem}.svg", bbox_inches="tight", facecolor="white")
    plt.close(fig)


def draw_pipeline() -> None:
    fig, ax = plt.subplots(figsize=(13, 10.1))
    ax.set_xlim(0, 13)
    ax.set_ylim(0, 10.1)
    ax.axis("off")

    stages = [
        (
            "三领域 Java 仓库与测试",
            "企业信息化 · 智慧物流 · 智慧城市\n定位目标文件并关联相关测试",
            "#EAF1F8",
        ),
        (
            "抽象语法树（AST）骨架与依赖抽取",
            "解析类型、字段和方法签名\n抽取目标骨架与项目内依赖闭包",
            "#EAF1F8",
        ),
        (
            "覆盖率候选与训练扩充",
            "合并相关测试的行覆盖率，按目标类 > 0.50 筛选\n补充训练候选以扩展代码生成样本",
            "#FCEEDB",
        ),
        (
            "基于源码生成中文需求",
            "GPT 6 Luna 依据完整目标源码提炼真实行为\n结合骨架说明类型结构与依赖边界",
            "#EAF4EA",
        ),
        (
            "构造监督样本对",
            "输入：中文需求与目标文件骨架\n目标：完整 Java 源文件",
            "#F1EAF8",
        ),
        (
            "仓库内划分与功能评测",
            "训练样本 984 条\n评估样本 220 条（按功能实验最终保留样本统计）",
            "#F1EAF8",
        ),
    ]

    x_positions = {0: 0.55, 1: 6.75}
    row_y = {0: 7.4, 1: 4.6, 2: 1.8}
    width, height = 5.7, 1.55
    positions = {
        0: (0, 0), 1: (0, 1), 2: (1, 1),
        3: (1, 0), 4: (2, 0), 5: (2, 1),
    }
    for i, (title, detail, color) in enumerate(stages):
        row, col = positions[i]
        x, y = x_positions[col], row_y[row]
        box = FancyBboxPatch(
            (x, y), width, height,
            boxstyle="round,pad=0.05,rounding_size=0.14",
            linewidth=1.25,
            edgecolor="#43566F",
            facecolor=color,
        )
        ax.add_patch(box)
        ax.text(x + width / 2, y + 1.04, title, ha="center", va="center",
                fontsize=14, fontweight="bold", color="#243447")
        ax.text(x + width / 2, y + 0.48, detail, ha="center", va="center",
                fontsize=11.5, linespacing=1.35, color="#243447")

    # Snake through the two-column, three-row layout: 1→2↓3←4↓5→6.
    arrows = [
        ((6.27, 8.175), (6.73, 8.175)),
        ((9.6, 7.38), (9.6, 6.2)),
        ((6.73, 5.375), (6.27, 5.375)),
        ((3.4, 4.58), (3.4, 3.37)),
        ((6.27, 2.575), (6.73, 2.575)),
    ]
    for start, end in arrows:
        ax.annotate("", xy=end, xytext=start,
                    arrowprops={"arrowstyle": "-|>", "lw": 1.5, "color": "#68798D"})

    ax.set_title("Java 文件级 SFT 数据构造流程", fontsize=18, pad=18, fontweight="bold")
    fig.tight_layout()
    save_figure(fig, "data_construction_flow")


def draw_domain_split(stats: dict) -> None:
    source = stats["sft_source_set"]
    task3 = stats["current_task3_samples"]
    domain_counts = {item["domain"]: item for item in source["domain_counts"]}
    shared_eval_counts = {item["domain"]: item["count"] for item in task3["domain_counts"]}
    domains = ["企业信息化", "智慧物流", "智慧城市"]
    x = list(range(len(domains)))
    width = 0.34
    series = [
        ("训练（n=984）", [domain_counts[d]["train"] for d in domains], "#527EA5"),
        ("评估（n=220）", [shared_eval_counts[d] for d in domains], "#E58B42"),
    ]

    fig, ax = plt.subplots(figsize=(10.8, 5.9))
    offsets = [-width / 2, width / 2]
    for (label, values, color), offset in zip(series, offsets):
        bars = ax.bar([p + offset for p in x], values, width, label=label, color=color)
        ax.bar_label(bars, padding=3, fontsize=9)

    ax.set_xticks(x, domains)
    ax.set_ylabel("样本数")
    ax.set_title("Java三领域训练与评估样本分布", fontsize=17, pad=12, fontweight="bold")
    ax.legend(frameon=False, ncols=2, loc="upper right", fontsize=9)
    ax.grid(axis="y", alpha=0.22)
    ax.set_axisbelow(True)
    ax.set_ylim(0, max(domain_counts[d]["train"] for d in domains) * 1.16)
    ax.text(
        0.0, -0.19,
        "评估样本为功能实验最终保留的 220 条，按领域为 76 / 102 / 42。",
        transform=ax.transAxes, ha="left", va="top", fontsize=9, color="#4B5563",
    )
    fig.tight_layout()
    save_figure(fig, "java_domain_split")


def main() -> None:
    configure_fonts()
    stats = json.loads(STATISTICS.read_text(encoding="utf-8"))
    draw_pipeline()
    draw_domain_split(stats)
    print(f"Wrote flow and split charts under {OUTPUT}")


if __name__ == "__main__":
    main()
