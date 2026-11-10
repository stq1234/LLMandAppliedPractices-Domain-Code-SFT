# 文献阅读记录

本文件用于报告编写与复核，不属于报告正文。

- aiXcoder-7B 原始论文：<https://arxiv.org/html/2410.13187v3>。
  - 1.2T unique tokens；自回归稠密 Transformer；32 层、隐藏维度 4096、中间维度 14464、32 个 query heads、8 个 KV heads、RoPE。
  - NTP、FIM、SFIM；SFIM 利用 AST 选择结构化片段，服务于代码补全。
  - 报告仅简要说明历史预训练背景，具体本次模型配置应以下载配置为准。
- aiXcoder-7B-v2 / CoLT：<https://arxiv.org/html/2503.15301v1>。
  - CoLT-132K 包括 120000 训练、12000 测试，Python/Java/C++/Go，任务为 API、单行、结构片段，原始长上下文最多 128K；模型实际输入按窗口截断。
  - 跨文件上下文按独立文件组织，依赖函数保留签名和注释、删去实现。
  - 先 SFT，再使用正确源码与采样错误输出构造偏好，执行 DPO。模型原始发布阶段使用的 DPO/LoRA 与本次领域全参数 SFT 必须区分。
  - 论文 Java CoLT 结构片段 EM：aiXcoder-7B 50.6%、v2 60.9%；CrossCodeEval Java EM：42.50%、51.43%。这些仅为文献结果，不能列为本实验实测。
- Transformer：<https://arxiv.org/abs/1706.03762>。
- ZeRO：<https://arxiv.org/abs/1910.02054>。

阅读日期：2026-11-10。报告采用释义并使用完整文献链接；不复制论文图表。
