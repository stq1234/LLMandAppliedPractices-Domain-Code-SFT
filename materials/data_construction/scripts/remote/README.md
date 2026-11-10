# Java Skeleton Extractor

该工具只扫描仓库的 `src` 侧 Java 文件，并排除 `test`、`tests`、构建输出和生成代码目录。每个源文件输出一行 JSONL，保存 `repo`（仓库文件夹名）、`repo_tag`（GitHub owner/repo）、仓库相对路径、包名、声明类型、导入、引用类型、源码哈希和不含字段初始值/方法体的骨架字符串。引用类型从完整源码提取，以便任务三沿方法体调用关系递归收集项目内依赖骨架。

在项目根目录运行：

```bash
./build_skeletons.sh
```

结果写入项目根目录的 `skeletons/<repo>/skeletons.jsonl`，源码从项目根目录的 `repos/<repo>` 读取。脚本逐仓库串行处理，单个仓库失败时立即退出。
