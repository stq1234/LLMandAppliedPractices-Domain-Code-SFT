#!/usr/bin/env python3
"""Fill server-generated task-3 drafts locally, then upload each 3.json."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import FIRST_COMPLETED, Future, ThreadPoolExecutor, wait
from pathlib import Path, PurePosixPath
from typing import Any


MAX_CONCURRENT_SAMPLES = 20

LOCAL_ROOT = Path(r"C:\Users\-stq-\Desktop\didaima")
REPOS_ROOT = Path(r"C:\Users\-stq-\Desktop\didaima\repos")
ARCHIVE_PATH = Path(r"C:\Users\-stq-\Desktop\didaima\task3-samples-draft.zip")
DRAFTS_ROOT = Path(r"C:\Users\-stq-\Desktop\didaima\drafts")
WORK_ROOT = Path(r"C:\Users\-stq-\Desktop\didaima\work")
PROMPT_PATH = Path(r"C:\Users\-stq-\Desktop\didaima\prompt.txt")
SERVER_ALIAS = "a6000"
REMOTE_ROOT = "/home/shaotianqi/LowCode"
REPOSITORY_NAME = re.compile(r"[A-Za-z0-9._-]+")
PROMPT_SYSTEM_MARKER = "[[SYSTEM]]"
PROMPT_USER_MARKER = "[[USER]]"
PROMPT_PLACEHOLDER = re.compile(r"\{\{([A-Z_]+)\}\}")
REQUIRED_PROMPT_PLACEHOLDERS = {
    "REPOSITORY_CONTEXT",
    "TARGET_FILE_SKELETONS",
    "DEPENDENCY_SKELETONS",
    "REFERENCE_SOURCES",
}


def load_prompt_roles() -> tuple[str, str]:
    """Load the plain-text system/user prompt template and validate its slots."""
    template = PROMPT_PATH.read_text(encoding="utf-8-sig")
    if template.count(PROMPT_SYSTEM_MARKER) != 1 \
            or template.count(PROMPT_USER_MARKER) != 1:
        raise ValueError(
            f"{PROMPT_PATH} 必须各包含一个 {PROMPT_SYSTEM_MARKER} 和 "
            f"{PROMPT_USER_MARKER} 标记"
        )
    prefix, system_and_user = template.split(PROMPT_SYSTEM_MARKER, 1)
    system_prompt, user_template = system_and_user.split(PROMPT_USER_MARKER, 1)
    if prefix.strip() or not system_prompt.strip() or not user_template.strip():
        raise ValueError(f"{PROMPT_PATH} 的角色分段格式错误")
    placeholders = set(PROMPT_PLACEHOLDER.findall(user_template))
    if placeholders != REQUIRED_PROMPT_PLACEHOLDERS:
        raise ValueError(
            f"{PROMPT_PATH} 动态字段应为 {sorted(REQUIRED_PROMPT_PLACEHOLDERS)}，"
            f"实际为 {sorted(placeholders)}"
        )
    if PROMPT_PLACEHOLDER.search(system_prompt):
        raise ValueError(f"{PROMPT_PATH} 的 system 段不能包含动态字段")
    return system_prompt.strip(), user_template.strip()


def render_user_prompt(template: str, values: dict[str, str]) -> str:
    """Substitute template slots without interpreting braces in Java source text."""
    placeholders = set(PROMPT_PLACEHOLDER.findall(template))
    missing = placeholders - values.keys()
    if missing:
        raise ValueError(f"prompt 动态字段没有值: {sorted(missing)}")

    def replace(match: re.Match[str]) -> str:
        return values[match.group(1)]

    return PROMPT_PLACEHOLDER.sub(replace, template)


def build_prompt_messages(sample: dict[str, Any]) -> list[dict[str, str]]:
    """Build Responses API role messages from one real task-3 sample."""
    repo = sample.get("repo")
    repo_tag = sample.get("repo_tag")
    target_paths = sample.get("target_paths")
    target_files = sample.get("target_files")
    skeletons = sample.get("skeletons")
    if not isinstance(repo, str) or not repo:
        raise ValueError("任务三样本缺少 repo")
    if not isinstance(repo_tag, str) or not repo_tag:
        raise ValueError(f"{repo}: 任务三样本缺少 repo_tag")
    if not isinstance(target_paths, list) or not target_paths:
        raise ValueError(f"{repo}: 任务三样本缺少 target_paths")
    if not isinstance(target_files, list) or not target_files:
        raise ValueError(f"{repo}: 任务三样本缺少 target_files")
    if not isinstance(skeletons, list) or not skeletons \
            or not all(isinstance(value, str) and value.strip() for value in skeletons):
        raise ValueError(f"{repo}: 任务三样本缺少 skeletons 字符串列表")

    repo_root = (REPOS_ROOT / repo).resolve()
    remaining_skeletons = list(skeletons)
    target_skeleton_sections: list[str] = []
    source_sections: list[str] = []
    ordered_paths: list[str] = []
    seen_paths: set[str] = set()
    for index, target in enumerate(target_files, start=1):
        if not isinstance(target, dict):
            raise ValueError(f"{repo}: target_files 第 {index} 项格式错误")
        relative = safe_relative_path(target.get("source_path"), repo, "source_path")
        if relative in seen_paths:
            raise ValueError(f"{repo}: target_files 包含重复路径: {relative}")
        seen_paths.add(relative)
        ordered_paths.append(relative)
        skeleton = target.get("skeleton")
        if not isinstance(skeleton, str) or not skeleton.strip():
            raise ValueError(f"{repo}: {relative} 缺少目标文件 skeleton")
        try:
            remaining_skeletons.remove(skeleton)
        except ValueError as error:
            raise ValueError(
                f"{repo}: {relative} 的目标 skeleton 不在顶层 skeletons 依赖闭包中"
            ) from error
        source_path = (repo_root / Path(relative)).resolve()
        if repo_root not in source_path.parents or not source_path.is_file():
            raise FileNotFoundError(f"本地目标源码不存在: {source_path}")
        target_skeleton_sections.append(
            f"目标文件 {index}\n路径：{relative}\n"
            f"<skeleton>\n{skeleton.rstrip()}\n</skeleton>"
        )
        source_sections.append(
            f"目标文件 {index}\n路径：{relative}\n"
            f"<source>\n{source_path.read_text(encoding='utf-8')}\n</source>"
        )

    normalized_target_paths = [
        safe_relative_path(value, repo, "target_paths") for value in target_paths
    ]
    if normalized_target_paths != ordered_paths:
        raise ValueError(f"{repo}: target_paths 与 target_files 的顺序或内容不一致")

    dependency_sections = [
        f"依赖骨架 {index}\n<skeleton>\n{skeleton.rstrip()}\n</skeleton>"
        for index, skeleton in enumerate(remaining_skeletons, start=1)
    ]
    repository_context = (
        f"仓库目录名：{repo}\n"
        f"上游仓库标识：{repo_tag}\n"
        "本次目标文件（按样本顺序）：\n"
        + "\n".join(f"{index}. {path}" for index, path in enumerate(
            normalized_target_paths, start=1))
    )
    system_prompt, user_template = load_prompt_roles()
    user_prompt = render_user_prompt(user_template, {
        "REPOSITORY_CONTEXT": repository_context,
        "TARGET_FILE_SKELETONS": "\n\n".join(target_skeleton_sections),
        "DEPENDENCY_SKELETONS": "\n\n".join(dependency_sections) or "（无）",
        "REFERENCE_SOURCES": "\n\n".join(source_sections),
    })
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]


def generate_requirement(sample: dict[str, Any]) -> str:
    # 按使用者要求，API 配置和 prompt 都是函数内部的明文本地变量。
    api_url = "http://localhost:8080/v1/responses"
    api_key = os.environ.get("LOWCODE_REQUIREMENTS_API_KEY", "")
    model = "gpt-5.6-luna"
    messages = build_prompt_messages(sample)
    # print(messages)
    # import os
    # os._exit(0)
    if not api_url or not api_key:
        return "stq"
    payload = {
        "model": model,
        "input": messages,
        "temperature": 0.2,
    }
    request = urllib.request.Request(
        api_url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json; charset=utf-8",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            # print(response.read())
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"OpenAI API HTTP {error.code}: {detail}") from error
    output_text = body.get("output_text")
    if not output_text:
        fragments: list[str] = []
        for item in body.get("output", []):
            for content in item.get("content", []):
                if content.get("type") in {"output_text", "text"} and content.get("text"):
                    fragments.append(content["text"])
        output_text = "\n".join(fragments)
    if not isinstance(output_text, str) or not output_text.strip():
        raise RuntimeError(f"API 响应中没有文本输出: {body}")
    return output_text.strip()


def run(command: list[str], *, input_text: str | None = None) -> None:
    subprocess.run(command, input=input_text, text=True, check=True)


def download_archive(destination: Path) -> None:
    """Download the latest server-side task-3 draft archive atomically."""
    destination = destination.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".download")
    temporary.unlink(missing_ok=True)
    try:
        run([
            "scp",
            f"{SERVER_ALIAS}:{REMOTE_ROOT}/task3-samples-draft.zip",
            str(temporary),
        ])
        temporary.replace(destination)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def safe_relative_path(value: Any, repo: str, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{repo}: {field} 必须是非空相对路径")
    normalized = value.replace("\\", "/")
    pure = PurePosixPath(normalized)
    if pure.is_absolute() or ".." in pure.parts:
        raise ValueError(f"{repo}: {field} 必须从仓库根目录的下一级开始: {value}")
    return normalized


def reset_drafts_directory() -> Path:
    WORK_ROOT.mkdir(parents=True, exist_ok=True)
    drafts = DRAFTS_ROOT
    root = LOCAL_ROOT.resolve()
    resolved = drafts.resolve()
    if root not in resolved.parents:
        raise RuntimeError(f"拒绝清理工作目录之外的路径: {resolved}")
    if drafts.exists():
        shutil.rmtree(drafts)
    drafts.mkdir()
    return drafts


def extract_drafts(archive: Path) -> Path:
    if not archive.is_file():
        raise FileNotFoundError(f"找不到任务三初稿压缩包: {archive}")
    drafts = reset_drafts_directory()
    seen: set[str] = set()
    with zipfile.ZipFile(archive) as bundle:
        files = [item for item in bundle.infolist() if not item.is_dir()]
        if not files:
            raise ValueError("任务三初稿压缩包为空")
        for item in files:
            path = PurePosixPath(item.filename)
            if len(path.parts) != 2 or path.name != "3.json":
                raise ValueError(f"压缩包内只允许 <repo>/3.json: {item.filename}")
            repo = path.parts[0]
            if not REPOSITORY_NAME.fullmatch(repo) or repo in seen:
                raise ValueError(f"非法或重复的仓库目录: {repo}")
            seen.add(repo)
            destination = drafts / repo / "3.json"
            destination.parent.mkdir()
            with bundle.open(item) as source, destination.open("wb") as target:
                shutil.copyfileobj(source, target)
    return drafts


def load_and_validate_samples(repo: str, sample_path: Path) -> list[dict[str, Any]]:
    repo_root = (REPOS_ROOT / repo).resolve()
    if not repo_root.is_dir() or repo_root.parent != REPOS_ROOT.resolve():
        raise FileNotFoundError(f"本地仓库不存在: {repo_root}")
    samples = json.loads(sample_path.read_text(encoding="utf-8"))
    if not isinstance(samples, list):
        raise ValueError(f"应为 JSON 数组: {sample_path}")
    for index, sample in enumerate(samples):
        if not isinstance(sample, dict):
            raise ValueError(f"{sample_path} 第 {index} 项不是对象")
        if sample.get("repo") != repo or not sample.get("repo_tag"):
            raise ValueError(f"{sample_path} 第 {index} 项缺少正确的 repo/repo_tag")
        target_paths = sample.get("target_paths")
        targets = sample.get("target_files")
        skeletons = sample.get("skeletons")
        if not isinstance(target_paths, list) or not target_paths:
            raise ValueError(f"{repo} 第 {index} 项缺少 target_paths")
        if not isinstance(targets, list) or not targets:
            raise ValueError(f"{repo} 第 {index} 项缺少 target_files")
        if not isinstance(skeletons, list) or not skeletons \
                or not all(isinstance(value, str) and value.strip() for value in skeletons):
            raise ValueError(f"{repo} 第 {index} 项缺少 skeletons 字符串列表")
        normalized_targets = [safe_relative_path(value, repo, "target_paths")
                              for value in target_paths]
        file_paths: list[str] = []
        for target in targets:
            if not isinstance(target, dict):
                raise ValueError(f"{repo} 第 {index} 项 target_files 格式错误")
            relative = safe_relative_path(target.get("source_path"), repo, "source_path")
            skeleton = target.get("skeleton")
            if not isinstance(skeleton, str) or not skeleton.strip():
                raise ValueError(f"{repo} 第 {index} 项 target_files 缺少 skeleton")
            if skeleton not in skeletons:
                raise ValueError(
                    f"{repo} 第 {index} 项目标 skeleton 不在顶层 skeletons 中"
                )
            source = (repo_root / Path(relative)).resolve()
            if repo_root not in source.parents or not source.is_file():
                raise FileNotFoundError(f"本地目标源码不存在: {source}")
            file_paths.append(relative)
        if normalized_targets != file_paths:
            raise ValueError(f"{repo} 第 {index} 项 target_paths 与 target_files 不一致")
    return samples


def write_samples(sample_path: Path, samples: list[dict[str, Any]]) -> None:
    temporary = sample_path.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(samples, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    temporary.replace(sample_path)


def fill_requirements(repository_files: list[Path]) -> int:
    if MAX_CONCURRENT_SAMPLES < 1:
        raise ValueError("MAX_CONCURRENT_SAMPLES 必须至少为 1")

    work_items: list[tuple[str, Path, list[dict[str, Any]], int]] = []
    for sample_path in repository_files:
        repo = sample_path.parent.name
        samples = load_and_validate_samples(repo, sample_path)
        work_items.extend(
            (repo, sample_path, samples, index)
            for index in range(len(samples))
            if not isinstance(samples[index].get("requirement"), str)
            or not samples[index]["requirement"].strip()
        )

    total = len(work_items)
    if total == 0:
        return 0

    work_iterator = iter(work_items)
    pending: dict[
        Future[str], tuple[str, Path, list[dict[str, Any]], int]
    ] = {}

    def submit_next(executor: ThreadPoolExecutor) -> bool:
        try:
            repo, sample_path, samples, index = next(work_iterator)
        except StopIteration:
            return False
        future = executor.submit(generate_requirement, samples[index])
        pending[future] = (repo, sample_path, samples, index)
        return True

    worker_count = min(MAX_CONCURRENT_SAMPLES, total)
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        for _ in range(worker_count):
            submit_next(executor)

        while pending:
            completed, _ = wait(pending, return_when=FIRST_COMPLETED)
            for future in completed:
                repo, sample_path, samples, index = pending.pop(future)
                try:
                    requirement = future.result()
                except Exception as error:
                    # 单个样本失败时保留空需求，继续处理其他样本。
                    samples[index]["requirement"] = ""
                    print(
                        f"[{repo}] requirement {index + 1}/{len(samples)} 失败，"
                        f"保留为空 ({type(error).__name__}: {error})"
                    )
                else:
                    samples[index]["requirement"] = requirement
                write_samples(sample_path, samples)
                if samples[index]["requirement"]:
                    print(
                        f"[{repo}] requirement {index + 1}/{len(samples)}: "
                        f"{samples[index].get('id')}"
                    )
                submit_next(executor)

    return total


REMOTE_VERIFY_SCRIPT = r'''import json
import pathlib
import re
import sys

repo = sys.argv[1]
if re.fullmatch(r"[A-Za-z0-9._-]+", repo) is None:
    raise SystemExit("invalid repo")
root = pathlib.Path("/home/shaotianqi/LowCode")
directory = root / "samples" / repo
upload = directory / "3.json.upload"
destination = directory / "3.json"
samples = json.loads(upload.read_text(encoding="utf-8"))
if not isinstance(samples, list):
    raise SystemExit("3.json must be an array")
for sample in samples:
    if sample.get("repo") != repo or not sample.get("repo_tag") or not sample.get("requirement"):
        raise SystemExit("invalid repo identity or empty requirement")
    for value in sample.get("target_paths", []):
        path = pathlib.PurePosixPath(value.replace("\\", "/"))
        if path.is_absolute() or ".." in path.parts:
            raise SystemExit("invalid target path")
upload.replace(destination)
counts = {}
for task in (1, 2, 3):
    path = directory / (str(task) + ".json")
    values = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else []
    counts["task" + str(task)] = len(values)
counts["total"] = sum(counts.values())
(directory / "metadata.json").write_text(
    json.dumps(counts, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(repo, len(samples))
'''


def upload_repository(repo: str, local_file: Path) -> None:
    remote = f"{SERVER_ALIAS}:{REMOTE_ROOT}/samples/{repo}/3.json.upload"
    run(["scp", str(local_file), remote])
    run(["ssh", SERVER_ALIAS, "python3", "-", repo], input_text=REMOTE_VERIFY_SCRIPT)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, default=ARCHIVE_PATH)
    parser.add_argument(
        "--from_draft",
        "--from-draft",
        action="store_true",
        help="从本地 drafts 目录续填缺少 requirement 的样本",
    )
    parser.add_argument("--no-upload", action="store_true", help="只在本地填充，不回传服务器")
    arguments = parser.parse_args()
    if not REPOS_ROOT.is_dir():
        raise FileNotFoundError(f"本地仓库根目录不存在: {REPOS_ROOT}")
    # if not arguments.no_download: // 不要了
    #     download_archive(arguments.archive)
    if arguments.from_draft:
        drafts = DRAFTS_ROOT
        if not drafts.is_dir():
            raise FileNotFoundError(f"找不到本地草稿目录: {drafts}")
    else:
        drafts = extract_drafts(arguments.archive)
    repository_files = sorted(drafts.glob("*/3.json"))
    if not repository_files:
        raise FileNotFoundError(f"本地草稿目录中没有 <repo>/3.json: {drafts}")
    total = fill_requirements(repository_files)
    for sample_path in repository_files:
        repo = sample_path.parent.name
        if not arguments.no_upload:
            samples = json.loads(sample_path.read_text(encoding="utf-8"))
            if any(
                not isinstance(sample.get("requirement"), str)
                or not sample["requirement"].strip()
                for sample in samples
            ):
                print(f"[{repo}] 存在空 requirement，本轮跳过上传")
                continue
            upload_repository(repo, sample_path)
    print(f"完成: {len(repository_files)} 个仓库，{total} 个任务三样本")


if __name__ == "__main__":
    main()
