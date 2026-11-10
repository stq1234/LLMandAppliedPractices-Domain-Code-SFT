# 面向特定领域的 Java 文件级代码合成实验材料

报告正文为 [实验报告.md](实验报告.md)，内容包括领域文件合成任务、数据构造、模型与监督微调方法、功能评测、错误分析和配对案例。Markdown 就近引用本地图片，附录保留完整训练配置、需求生成提示与扩展案例。远程来源、文件对应关系和复核记录集中保存在工程材料中。

## 内容

- `materials/training/`：训练入口、分布式配置、模型配置、训练日志、事件数据及训练数据统计和逐样本索引。
- `materials/data_construction/`：仓库筛选、覆盖率、文件样本和需求构造的脚本、配置及完整流程说明；包含原服务器端构造模块与本地需求生成模块。
- `materials/evaluation/`：预测、回填和测试程序，以及两次已有评测的结果快照。
- `analysis/`：数据汇总、配对分析、来源映射及复核说明。
- `figures/`、`analysis/figures/`、`materials/data_construction/figures/`：报告引用的模型架构、流程、数据分布、训练与结果图；同时保留 PNG 和 SVG。

材料包含从 `jinan` 和 `a6000` 只读下载的脚本，以及原本地需求生成模块的脱敏副本。未在远程创建或修改文件，未重新训练或重新调用模型。数据构造按“仓库筛选—测试覆盖率—骨架提取—需求生成—监督划分”组织为统一逻辑流程，模块关系与输入输出见 [数据构造说明](materials/data_construction/README.md)。脚本中的服务地址、数据位置和启动方式属于原运行环境；归档代码没有在本机执行完整数据流水线。

本地不包含模型权重、训练原始 JSONL 全文或完整原始 Git 仓库。训练数据、结果与脚本的具体来源见各材料目录的清单。报告数值由现存实验结果重新汇总，不使用论文指标代替本次评测。

代码和实验材料在本仓库中归档。

## 阅读与复核

- 训练配方、数据关联和曲线统计：[training_notes.md](analysis/training_notes.md)。
- Java 仓库、覆盖率、需求与 SFT 划分：[data_notes.md](materials/data_construction/analysis/data_notes.md)。
- 功能评测、离线预测来源和代表案例：[evaluation_notes.md](analysis/evaluation_notes.md)。
- 本次补充的错误分类：[error_classification_rules.md](analysis/error_classification_rules.md)、[error_analysis_summary.json](analysis/error_analysis_summary.json)。逐文件标签位于 `analysis/error_classification.csv`。
- 需求生成流程与展示材料：[data_revision_notes.md](materials/data_construction/analysis/data_revision_notes.md)、[appendix_materials.md](materials/data_construction/analysis/appendix_materials.md)。
- 检查点与训练图核查：[training_revision_notes.md](analysis/training_revision_notes.md)。
- 最终分析集合与历史训练监控的统计范围：[data_split_revision_notes.md](analysis/data_split_revision_notes.md)。
- 报告扩充所采用的分析角度：[report_expansion_notes.md](analysis/report_expansion_notes.md)。
- Java 长度分布与样本来源组成：[data_expansion_notes.md](materials/data_construction/analysis/data_expansion_notes.md)、[data_complexity_summary.csv](materials/data_construction/analysis/data_complexity_summary.csv)。
- 序列、掩码与梯度诊断：[training_expansion_notes.md](analysis/training_expansion_notes.md)、[training_gradient_diagnostics.csv](analysis/training_gradient_diagnostics.csv)。
- 阶段结果、长度分层、宏平均与完整业务案例：[evaluation_expansion_notes.md](analysis/evaluation_expansion_notes.md)、[extended_evaluation_summary.json](analysis/extended_evaluation_summary.json)。逐文件补充指标为 `analysis/extended_sample_metrics.csv`。
- 图表数据：[report_data.json](analysis/report_data.json)、[training_metrics.csv](analysis/training_metrics.csv)。逐文件配对表为 `analysis/paired_results.csv`，逐训练步数据为 `analysis/training_step_metrics.csv`。
- 远程来源清单保存在各 `materials` 子目录；训练和评测原始结果仅下载，不在本机重新运行。

训练过程分析对象是领域微调训练版本 V4；离线预测属于历史版本 0826（原服务标识为 `aixcoder-7b-v2-sft-0826`）。两者的来源对应关系保存在工程记录中；现存记录不能将功能结果对应至 V4 的具体权重检查点，也不能确定历史训练的 GPU 板卡型号。报告中的训练诊断与功能比较不构成逐轮检查点对照实验。固定 star 门槛未获限定范围源码与文档支持，报告采用仓库 star 快照。需求生成模型的正文名称统一为 GPT 6 Luna，旧脚本配置作为工程资料保留。

在本作业目录中，可重新生成本报告图形并校验关键数字：

```powershell
python -B analysis/plot_report.py
python -B analysis/plot_model_architecture.py
python -B materials/data_construction/analysis/plot_data_pipeline.py
python -B analysis/plot_error_analysis.py
python -B materials/data_construction/analysis/analyze_data_complexity.py
python -B analysis/plot_training_diagnostics.py
python -B analysis/analyze_extended_evaluation.py
python -B analysis/verify_report.py
```

绘图需要 Python、Matplotlib 与 NumPy。已有的 HTML/PDF 渲染及检查脚本保留为辅助代码，本次报告以 Markdown 和其引用图片为交付内容，不保留 PDF、HTML 衍生文件。

校验程序对照两份原始 `report.json` 检查 220 条配对目标、领域状态计数及全部通过样本的测试命令状态；对照训练日志检查 10 轮验证损失与 2,280 个训练步；独立核对 1,226 条原始 Java 来源映射，以及最终分析范围内 984 条训练记录、220 条共享评估样本的分布中位数；另检查 220 个文件的长度分组、仓库宏平均、梯度摘要及 12 幅图、13 张表、16 项参考文献的引用与结构。结果写入 `analysis/verification.json`。以上报告工具不连接服务器，不启动训练、推理或项目测试。

需求构造脚本副本中的认证值已改为从环境变量读取，具体说明见数据构造材料的 `source_manifest.json`；原始本地文件及远程文件均保持原状。
