#!/usr/bin/env python3
"""Plot V4's recorded gradient norm and learning-rate diagnostics."""

from __future__ import annotations

import csv
import statistics
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "analysis" / "training_step_metrics.csv"
DERIVED = ROOT / "analysis" / "training_gradient_diagnostics.csv"
OUTPUT = ROOT / "figures"
EXPECTED_STEPS = 2280
WARMUP_STEPS = 114
ROLLING_WINDOW = 50


def read_metrics() -> list[dict[str, float]]:
    with SOURCE.open(encoding="utf-8-sig", newline="") as f:
        rows = [
            {
                "step": int(row["step"]),
                "grad_norm": float(row["grad_norm"]),
                "learning_rate": float(row["learning_rate"]),
            }
            for row in csv.DictReader(f)
        ]
    if len(rows) != EXPECTED_STEPS:
        raise ValueError(f"Expected {EXPECTED_STEPS} steps, got {len(rows)}")
    if [row["step"] for row in rows] != list(range(1, EXPECTED_STEPS + 1)):
        raise ValueError("Step column must be contiguous from 1 through 2280")
    if any(row["grad_norm"] <= 0 for row in rows):
        raise ValueError("Log-scale plotting requires positive grad_norm values")
    return rows


def add_rolling_median(rows: list[dict[str, float]]) -> None:
    norms = [row["grad_norm"] for row in rows]
    for i, row in enumerate(rows):
        start = max(0, i - ROLLING_WINDOW + 1)
        row["grad_norm_50step_median"] = statistics.median(norms[start : i + 1])
        row["phase"] = "warmup" if row["step"] <= WARMUP_STEPS else "decay"


def write_derived_csv(rows: list[dict[str, float]]) -> None:
    fields = ["step", "learning_rate", "grad_norm", "grad_norm_50step_median", "phase"]
    with DERIVED.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row[field] for field in fields})


def draw(rows: list[dict[str, float]]) -> None:
    available = {entry.name for entry in font_manager.fontManager.ttflist}
    font = next(
        (name for name in ("Microsoft YaHei", "SimHei", "Noto Sans CJK SC") if name in available),
        "DejaVu Sans",
    )
    plt.rcParams.update(
        {
            "font.family": font,
            "axes.unicode_minus": False,
            "font.size": 9,
            "svg.fonttype": "none",
            "savefig.dpi": 220,
        }
    )

    steps = [row["step"] for row in rows]
    norms = [row["grad_norm"] for row in rows]
    medians = [row["grad_norm_50step_median"] for row in rows]
    learning_rates = [row["learning_rate"] for row in rows]
    peak_idx = max(range(len(norms)), key=norms.__getitem__)

    fig, axes = plt.subplots(
        2,
        1,
        figsize=(12.5, 7.2),
        sharex=True,
        gridspec_kw={"height_ratios": [2.2, 1.2]},
    )
    fig.suptitle("训练过程中的梯度范数与学习率", fontsize=15, fontweight="bold", y=0.98)

    ax = axes[0]
    ax.axvspan(1, WARMUP_STEPS, color="#E7EDF8", alpha=0.8, zorder=0)
    ax.axvline(WARMUP_STEPS, color="#667A9B", lw=1.0, ls="--", zorder=1)
    ax.scatter(steps, norms, s=7, color="#8EA1B2", alpha=0.45, label="逐步记录值", zorder=2)
    ax.plot(steps, medians, color="#176C85", lw=1.8, label="50 步滚动中位数", zorder=3)
    ax.set_yscale("log")
    ax.set_ylabel("记录的梯度范数（对数尺度）")
    ax.set_title("梯度范数记录", loc="left", fontsize=11)
    ax.legend(frameon=False, loc="upper right")
    ax.grid(axis="y", which="both", alpha=0.2)
    ax.set_axisbelow(True)
    ax.annotate(
        f"峰值 {norms[peak_idx]:.2f}（第 {steps[peak_idx]} 步）",
        xy=(steps[peak_idx], norms[peak_idx]),
        xytext=(steps[peak_idx] + 130, norms[peak_idx] / 2.5),
        arrowprops={"arrowstyle": "->", "color": "#536878", "lw": 0.9},
        fontsize=8.5,
        color="#354654",
    )
    ax.text(
        0.012,
        0.96,
        f"预热：第 1–{WARMUP_STEPS} 步",
        transform=ax.transAxes,
        ha="left",
        va="top",
        fontsize=8,
        color="#536878",
    )

    ax = axes[1]
    ax.axvspan(1, WARMUP_STEPS, color="#E7EDF8", alpha=0.8, zorder=0)
    ax.axvline(WARMUP_STEPS, color="#667A9B", lw=1.0, ls="--", zorder=1)
    ax.plot(steps, learning_rates, color="#8A69A6", lw=1.6)
    ax.set_ylabel("学习率")
    ax.set_xlabel("优化步数")
    ax.set_title("学习率调度", loc="left", fontsize=11)
    ax.set_xlim(0, EXPECTED_STEPS)
    ticks = [0, WARMUP_STEPS, 456, 912, 1368, 1824, EXPECTED_STEPS]
    ax.set_xticks(ticks, [f"{step:,}" for step in ticks])
    ax.grid(axis="y", alpha=0.2)
    ax.set_axisbelow(True)

    fig.text(
        0.08,
        0.018,
        "grad_norm 按训练日志字段原值呈现。",
        fontsize=8,
        color="#536878",
    )
    fig.tight_layout(rect=[0.03, 0.05, 0.99, 0.95], h_pad=1.0)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUTPUT / "training_gradient_diagnostics.png", bbox_inches="tight", facecolor="white")
    fig.savefig(OUTPUT / "training_gradient_diagnostics.svg", bbox_inches="tight", facecolor="white")
    plt.close(fig)


def main() -> None:
    rows = read_metrics()
    add_rolling_median(rows)
    write_derived_csv(rows)
    draw(rows)
    peak = max(rows, key=lambda row: row["grad_norm"])
    print(
        f"Wrote gradient diagnostics for {len(rows)} steps; "
        f"logged grad_norm peak={peak['grad_norm']:.6f} at step {peak['step']}."
    )


if __name__ == "__main__":
    main()
