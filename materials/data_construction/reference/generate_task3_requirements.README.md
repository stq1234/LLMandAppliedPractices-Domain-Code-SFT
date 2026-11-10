# 任务三本地需求填充器

任务三现在采用“服务器生成初稿 → 本地生成自然语言需求 → 自动回传服务器”的顺序。

1. 在服务器运行 `~/LowCode/build_samples.sh`（或 `--tasks 3`），得到 `/home/shaotianqi/LowCode/task3-samples-draft.zip`。默认会保留已有任务三样本，并只补充新 ID；需要完全重建任务三时显式加 `--cover`。
2. 在 LowCode 根目录运行：

```powershell
cd C:\Users\-stq-\Desktop\didaima
python .\generate_task3_requirements.py
```

脚本会先通过 `scp a6000:/home/shaotianqi/LowCode/task3-samples-draft.zip` 自动下载最新草稿到
`C:\Users\-stq-\Desktop\didaima\task3-samples-draft.zip`，再继续解压和处理。
如果要跳过下载、使用本地已有压缩包，运行 `python .\generate_task3_requirements.py --no-download`。

脚本安全解压压缩包，发现多少个 `<repo>/3.json` 就处理多少个；已有非空 `requirement` 的样本会被永久保留，脚本只为缺少需求内容的样本生成需求。源码固定从
`C:\Users\-stq-\Desktop\didaima\repos\<repo>\<relative-path>` 定位并检查目标文件存在。
需求生成会从 `prompt.txt` 读取 `[[SYSTEM]]` 和 `[[USER]]` 两个角色的基础提示，运行时再拼接仓库标识、
目标路径、目标文件 skeleton、项目内业务依赖 skeleton 闭包以及本地目标文件的完整源码。初稿中的
`target_files[*].skeleton` 用于标识目标骨架，顶层 `skeletons` 中余下的内容作为依赖上下文；两者不会混为目标文件。
填充后，每个 `3.json` 会经 SCP 上传为
`/home/shaotianqi/LowCode/samples/<repo>/3.json.upload`，服务器校验成功后原子覆盖 `3.json`。

OpenAI API 的 `api_url`、`api_key` 和模型在 `generate_task3_requirements.py` 的
`generate_requirement()` 内，基础 prompt 位于 `prompt.txt`。当前 URL 或 key 为空时，
同一个函数直接返回 `stq`，因此可以完整测试解压、填充和回传流程。配置齐全后才会调用 API。

只测试本地填充、不上传：

```powershell
python .\generate_task3_requirements.py --no-upload
```

也可以组合使用 `--no-download --no-upload`，完全使用本地已有压缩包且不回传服务器。

解压及填充后的草稿位于 `C:\Users\-stq-\Desktop\didaima\drafts`，其他工作文件位于 `C:\Users\-stq-\Desktop\didaima\work`。
