"""Rebuild report figures from archived experimental summaries; no remote access."""
from pathlib import Path
import csv
import json

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "figures"
OUT.mkdir(exist_ok=True)
available = {f.name for f in font_manager.fontManager.ttflist}
font = next((x for x in ["Microsoft YaHei", "SimHei", "Noto Sans CJK SC"] if x in available), "DejaVu Sans")
plt.rcParams.update({"font.family": font, "axes.unicode_minus": False,
                     "font.size": 11, "axes.spines.top": False, "axes.spines.right": False,
                     "savefig.dpi": 200})
data = json.loads((ROOT / "analysis/report_data.json").read_text(encoding="utf-8"))


def save(fig, name):
    fig.savefig(OUT / (name + ".png"), bbox_inches="tight", facecolor="white")
    fig.savefig(OUT / (name + ".svg"), bbox_inches="tight", facecolor="white")
    plt.close(fig)


def evaluation_comparison():
    rows = data["evaluation"] + [dict(domain="全部样本", **data["evaluation_total"])]
    for row in rows:
        assert row["base_pass"] + row["base_fail"] + row["base_error"] == row["n"]
        assert row["sft_pass"] + row["sft_fail"] + row["sft_error"] == row["n"]
    x = np.arange(len(rows))
    width = 0.34
    fig, ax = plt.subplots(figsize=(10, 4.7))
    for offset, key, color, label in [(-width / 2, "base_pass", "#7893AC", "基础预测"),
                                      (width / 2, "sft_pass", "#167D9A", "领域微调预测")]:
        values = [100 * r[key] / r["n"] for r in rows]
        bars = ax.bar(x + offset, values, width, label=label, color=color)
        ax.bar_label(bars, labels=[f"{v:.2f}%" for v in values], padding=5, fontsize=10)
    ax.set_xticks(x, [f"{r['domain']}\n(n={r['n']})" for r in rows])
    ax.set_ylim(0, 105)
    ax.set_ylabel("样本通过率（%）")
    ax.set_yticks(range(0, 101, 20))
    ax.grid(axis="y", alpha=0.2)
    ax.set_axisbelow(True)
    ax.legend(loc="upper left", ncol=2, frameon=False)
    fig.tight_layout()
    save(fig, "01_domain_pass_rates")


def training_curves():
    path = ROOT / "analysis/training_metrics.csv"
    with path.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    epoch = [float(r["epoch"]) for r in rows]
    train = [float(r["train_loss_mean"]) for r in rows]
    validation = [float(r["eval_loss"]) for r in rows]
    assert len(rows) == 10
    fig, axes = plt.subplots(1, 2, figsize=(10.8, 4.5))
    fig.suptitle("SFT 每轮训练与验证损失", fontsize=15, fontweight="bold")
    axes[0].plot(epoch, train, "o-", color="#167D9A", lw=2)
    axes[0].set_title("逐步训练损失的轮均值")
    axes[0].set_yscale("log")
    axes[0].set_ylabel("训练损失均值（对数刻度）")
    axes[1].plot(epoch, validation, "s-", color="#C56842", lw=2)
    axes[1].set_title("每轮验证损失")
    axes[1].set_ylabel("验证损失")
    min_index = int(np.argmin(validation))
    axes[1].annotate(
        f"最低值 {min(validation):.4f}",
        xy=(epoch[min_index], validation[min_index]),
        xytext=(epoch[min_index] + 1.0, validation[min_index] + 0.045),
        arrowprops={"arrowstyle": "->", "color": "#555"},
        fontsize=10,
    )
    for ax in axes:
        ax.set_xlabel("训练轮次")
        ax.set_xticks(epoch)
        ax.grid(alpha=0.2)
        ax.set_axisbelow(True)
    fig.tight_layout(rect=[0, 0, 1, 0.92], w_pad=3)
    save(fig, "02_training_validation_loss")


def training_step_curves():
    path = ROOT / "analysis/training_step_metrics.csv"
    with path.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 2280
    step = np.array([int(r["step"]) for r in rows], dtype=int)
    train = np.array([float(r["train_loss"]) for r in rows], dtype=float)
    learning_rate = np.array([float(r["learning_rate"]) for r in rows], dtype=float)
    eval_rows = [r for r in rows if r["eval_loss"]]
    eval_step = np.array([int(r["step"]) for r in eval_rows], dtype=int)
    eval_loss = np.array([float(r["eval_loss"]) for r in eval_rows], dtype=float)

    window = 50
    cumulative = np.cumsum(train)
    counts = np.minimum(np.arange(1, len(train) + 1), window)
    rolling = np.convolve(train, np.ones(window), mode="full")[: len(train)] / counts

    fig, axes = plt.subplots(
        3,
        1,
        figsize=(11.2, 8.2),
        sharex=True,
        gridspec_kw={"height_ratios": [1.45, 1.0, 1.0]},
    )
    fig.suptitle("SFT 逐步训练损失、验证损失与学习率", fontsize=15, fontweight="bold")
    axes[0].plot(step, train, color="#9FB4C5", lw=0.75, alpha=0.75, label="逐步训练损失")
    axes[0].plot(step, rolling, color="#167D9A", lw=2.0, label="50 步移动均值")
    axes[0].set_ylabel("训练损失")
    axes[0].set_title("逐步训练损失与移动均值", loc="left", fontsize=11)
    axes[0].legend(frameon=False, ncol=2, loc="upper right")

    axes[1].plot(eval_step, eval_loss, "o-", color="#C56842", lw=1.8, ms=5)
    axes[1].set_ylabel("验证损失")
    axes[1].set_title("每轮验证损失", loc="left", fontsize=11)

    axes[2].plot(step, learning_rate, color="#6A64A8", lw=1.7)
    axes[2].set_ylabel("学习率")
    axes[2].set_xlabel("训练步数")
    axes[2].set_title("学习率调度", loc="left", fontsize=11)
    axes[2].set_xlim(1, int(step[-1]))
    axes[2].set_xticks(
        np.concatenate(([0], eval_step)),
        ["0"] + [f"{s}\n第{i}轮" for i, s in enumerate(eval_step, start=1)],
    )

    for ax in axes:
        for boundary in eval_step:
            ax.axvline(boundary, color="#D6DCE1", lw=0.7, zorder=0)
        ax.grid(axis="y", alpha=0.22)
        ax.set_axisbelow(True)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    save(fig, "training_metrics")


if __name__ == "__main__":
    evaluation_comparison()
    training_curves()
    training_step_curves()
    print("Rebuilt report figures (PNG and SVG).")
