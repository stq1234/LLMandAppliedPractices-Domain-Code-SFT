from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import Circle, FancyBboxPatch, FancyArrowPatch

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "figures"
OUT.mkdir(exist_ok=True)

available = {entry.name for entry in font_manager.fontManager.ttflist}
font = next(
    (name for name in ("Microsoft YaHei", "SimHei", "Noto Sans CJK SC") if name in available),
    "DejaVu Sans",
)
plt.rcParams.update({
    "font.family": font,
    "axes.unicode_minus": False,
    "font.size": 9,
    "svg.fonttype": "none",
    "savefig.dpi": 220,
})


def rounded_box(ax, xy, width, height, label, facecolor, edgecolor="#506476",
                fontsize=9, weight="normal", radius=0.10):
    patch = FancyBboxPatch(
        xy,
        width,
        height,
        boxstyle=f"round,pad=0.04,rounding_size={radius}",
        facecolor=facecolor,
        edgecolor=edgecolor,
        linewidth=1.2,
        zorder=3,
    )
    ax.add_patch(patch)
    ax.text(
        xy[0] + width / 2,
        xy[1] + height / 2,
        label,
        ha="center",
        va="center",
        fontsize=fontsize,
        fontweight=weight,
        linespacing=1.25,
        zorder=4,
    )
    return patch


def arrow(ax, start, end, color="#536878", lw=1.35, style="-|>", zorder=2):
    ax.add_patch(
        FancyArrowPatch(
            start,
            end,
            arrowstyle=style,
            mutation_scale=12,
            linewidth=lw,
            color=color,
            shrinkA=2,
            shrinkB=2,
            zorder=zorder,
        )
    )


def draw_architecture():
    fig = plt.figure(figsize=(15, 10.8), facecolor="white")
    ax = fig.add_axes([0.015, 0.02, 0.97, 0.96])
    ax.set_xlim(0, 15)
    ax.set_ylim(0, 10.7)
    ax.axis("off")

    ax.text(
        7.5, 10.48, "AiXcoder 7B V2：LLaMA 式因果语言模型",
        ha="center", va="center", fontsize=17, fontweight="bold",
    )
    ax.text(
        7.5, 10.15,
        "配置：32 层 · d=4,096 · SwiGLU=14,464 · RoPE θ=256,000 · 训练序列上限=32,768",
        ha="center", va="center", fontsize=10, color="#354654",
    )

    top_y, top_h = 9.12, 0.76
    top_nodes = [
        (0.35, 1.72, "输入词元 ID\n上下文与目标", "#EAF2F8"),
        (2.35, 1.95, "词嵌入矩阵\n49,152 × 4,096", "#DDECF5"),
        (4.68, 2.38, "Decoder blocks × 32\nhidden size = 4,096", "#E9E3F5"),
        (7.45, 1.62, "末层 RMSNorm\nε = 1e-6", "#E4F1EA"),
        (9.42, 2.08, "词表输出头\n4,096 → 49,152\n独立权重", "#F9E9D8"),
        (11.88, 2.72, "词表 logits\n49,152 个下一词元分数", "#F5E3E0"),
    ]
    for x, width, label, color in top_nodes:
        rounded_box(ax, (x, top_y), width, top_h, label, color, fontsize=9, weight="bold")
    for left, right in zip(top_nodes, top_nodes[1:]):
        arrow(ax, (left[0] + left[1] + 0.03, top_y + top_h / 2),
              (right[0] - 0.03, top_y + top_h / 2))

    arrow(
        ax,
        (5.87, top_y - 0.02),
        (5.87, 8.42),
        color="#7F6FA9",
        lw=1.3,
        style="-|>",
    )
    ax.text(6.05, 8.67, "单层结构放大", ha="left", va="center", fontsize=8, color="#665987")

    # Decoder block, shown as two pre-norm residual sublayers.
    panel = FancyBboxPatch(
        (0.35, 4.22), 14.25, 4.13,
        boxstyle="round,pad=0.05,rounding_size=0.12",
        facecolor="#FCFCFD",
        edgecolor="#7C8994",
        linewidth=1.2,
        linestyle=(0, (4, 3)),
        zorder=0,
    )
    ax.add_patch(panel)
    ax.text(
        0.62, 8.11, "单个 Decoder block（Pre-RMSNorm；按配置重复 32 次）",
        ha="left", va="center", fontsize=11, fontweight="bold", color="#263746",
    )

    # Self-attention sublayer.
    left_panel = FancyBboxPatch(
        (0.62, 4.55), 6.55, 3.22,
        boxstyle="round,pad=0.04,rounding_size=0.10",
        facecolor="#F1F7FB",
        edgecolor="#B2C5D2",
        linewidth=1.0,
        zorder=1,
    )
    ax.add_patch(left_panel)
    ax.text(0.88, 7.48, "① 自注意力子层", ha="left", va="center",
            fontsize=10, fontweight="bold", color="#31536A")

    row_y, row_h = 6.51, 0.70
    rounded_box(ax, (0.86, row_y), 0.78, row_h, "x_l", "#FFFFFF", fontsize=10, weight="bold")
    rounded_box(ax, (1.94, row_y), 1.27, row_h, "RMSNorm\nε=1e-6", "#E4F1EA", fontsize=8.5)
    rounded_box(
        ax, (3.53, row_y - 0.10), 2.34, 0.90,
        "GQA 自注意力\n查询头 Q=32 · 键值头 KV=8\nRoPE 用于 Q/K；因果注意力掩码",
        "#DDECF5", fontsize=8.1, weight="bold",
    )
    ax.add_patch(Circle((6.22, row_y + row_h / 2), 0.28,
                        facecolor="#FFF2D5", edgecolor="#9D7A35", lw=1.2, zorder=3))
    ax.text(6.22, row_y + row_h / 2, "+", ha="center", va="center",
            fontsize=13, fontweight="bold", color="#6A5423", zorder=4)
    ax.text(6.67, row_y + row_h / 2, "h_l", ha="left", va="center",
            fontsize=9.5, fontweight="bold", color="#354654")

    arrow(ax, (1.64, row_y + row_h / 2), (1.94, row_y + row_h / 2))
    arrow(ax, (3.21, row_y + row_h / 2), (3.53, row_y + row_h / 2))
    arrow(ax, (5.87, row_y + row_h / 2), (5.94, row_y + row_h / 2))
    arrow(ax, (6.50, row_y + row_h / 2), (6.66, row_y + row_h / 2))

    skip_y = 5.53
    ax.plot([1.25, 1.25, 6.22], [row_y, skip_y, skip_y],
            color="#7F8E9A", lw=1.25, zorder=2)
    arrow(ax, (6.22, skip_y), (6.22, row_y + 0.03),
          color="#7F8E9A", lw=1.25)
    ax.text(3.25, skip_y - 0.20, "残差支路：直接加回 x_l",
            ha="center", va="center", fontsize=8.1, color="#536878")

    # Feed-forward sublayer.
    right_panel = FancyBboxPatch(
        (7.37, 4.55), 6.58, 3.22,
        boxstyle="round,pad=0.04,rounding_size=0.10",
        facecolor="#FCF7F0",
        edgecolor="#D7C2A5",
        linewidth=1.0,
        zorder=1,
    )
    ax.add_patch(right_panel)
    ax.text(7.63, 7.48, "② 前馈子层", ha="left", va="center",
            fontsize=10, fontweight="bold", color="#785934")

    rounded_box(ax, (7.62, row_y), 0.77, row_h, "h_l", "#FFFFFF", fontsize=10, weight="bold")
    rounded_box(ax, (8.68, row_y), 1.27, row_h, "RMSNorm\nε=1e-6", "#E4F1EA", fontsize=8.5)
    rounded_box(
        ax, (10.25, row_y - 0.10), 2.42, 0.90,
        "SwiGLU 前馈网络\nSiLU gate × up projection\n4,096 → 14,464 → 4,096",
        "#F9E9D8", fontsize=8.1, weight="bold",
    )
    ax.add_patch(Circle((13.02, row_y + row_h / 2), 0.28,
                        facecolor="#FFF2D5", edgecolor="#9D7A35", lw=1.2, zorder=3))
    ax.text(13.02, row_y + row_h / 2, "+", ha="center", va="center",
            fontsize=13, fontweight="bold", color="#6A5423", zorder=4)
    ax.text(13.47, row_y + row_h / 2, "x_(l+1)", ha="left", va="center",
            fontsize=9.5, fontweight="bold", color="#354654")

    arrow(ax, (8.39, row_y + row_h / 2), (8.68, row_y + row_h / 2))
    arrow(ax, (9.95, row_y + row_h / 2), (10.25, row_y + row_h / 2))
    arrow(ax, (12.67, row_y + row_h / 2), (12.74, row_y + row_h / 2))
    arrow(ax, (13.30, row_y + row_h / 2), (13.47, row_y + row_h / 2))

    ax.plot([8.00, 8.00, 13.02], [row_y, skip_y, skip_y],
            color="#7F8E9A", lw=1.25, zorder=2)
    arrow(ax, (13.02, skip_y), (13.02, row_y + 0.03),
          color="#7F8E9A", lw=1.25)
    ax.text(10.48, skip_y - 0.20, "残差支路：直接加回 h_l",
            ha="center", va="center", fontsize=8.1, color="#536878")

    # The loss mask is distinct from the causal attention mask.
    loss_panel = FancyBboxPatch(
        (0.35, 0.62), 14.25, 3.25,
        boxstyle="round,pad=0.05,rounding_size=0.12",
        facecolor="#F7F8FA",
        edgecolor="#9DA8B1",
        linewidth=1.1,
        zorder=0,
    )
    ax.add_patch(loss_panel)
    ax.text(
        0.62, 3.56, "训练目标与两种 mask（分开理解）",
        ha="left", va="center", fontsize=11, fontweight="bold", color="#263746",
    )

    token_y, token_h = 2.48, 0.62
    token_nodes = [
        (0.72, 3.15, "提示与上下文\n骨架、需求与上下文", "#EAF2F8"),
        (3.98, 2.52, "响应标记\n源码开始标记", "#EEF0F2"),
        (6.62, 5.32, "目标源码词元\nsource 字段", "#E3F1E8"),
        (12.10, 1.88, "结束词元\n</s>", "#E3F1E8"),
    ]
    for x, width, label, color in token_nodes:
        rounded_box(ax, (x, token_y), width, token_h, label, color, fontsize=8.7, weight="bold")

    label_y, label_h = 1.65, 0.48
    rounded_box(ax, (0.72, label_y), 5.78, label_h,
                "损失标签 = IGNORE_INDEX（源码不计入损失）", "#ECEFF2",
                edgecolor="#8A949D", fontsize=8.3)
    rounded_box(ax, (6.62, label_y), 7.36, label_h,
                "下一词元损失生效（目标源码与 </s>）", "#D4E8D9",
                edgecolor="#6E9279", fontsize=8.3)
    ax.text(
        0.72, 1.20,
        "仅对补全文本计算损失：提示保留为条件上下文；数据整理器从响应标记后监督源码生成。",
        ha="left", va="center", fontsize=8.8, color="#354654",
    )
    ax.text(
        0.72, 0.88,
        "因果注意力掩码屏蔽未来词元，作用于注意力矩阵；它与上方的损失标签掩码不同。",
        ha="left", va="center", fontsize=8.8, color="#354654",
    )

    ax.text(
        0.36, 0.22,
        "结构参考：Touvron et al. (2023), LLaMA 2.",
        ha="left", va="center", fontsize=7.8, color="#57636D",
    )

    fig.savefig(OUT / "model_architecture.png", bbox_inches="tight", facecolor="white")
    fig.savefig(OUT / "model_architecture.svg", bbox_inches="tight", facecolor="white")
    plt.close(fig)


if __name__ == "__main__":
    draw_architecture()
    print("Wrote figures/model_architecture.png and .svg")
