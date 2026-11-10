#!/usr/bin/env python3
"""Build task-3 Java SFT records from existing sample JSON files."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

LINE_BREAK_RE = re.compile(r"\r\n|[\n\v\f\r\x1c-\x1e\x85\u2028\u2029]")
EVAL_SAMPLE_RATIO_DENOMINATOR = 5
EVAL_RANDOM_SEED = 42


@dataclass(frozen=True)
class BuiltRecord:
    repository: str
    sample_key: tuple[str, int]
    record: dict[str, Any]


def split_java_lines(value: str) -> list[str]:
    return LINE_BREAK_RE.split(value)


def comment_similar_code(code: str) -> str:
    lines = split_java_lines(code)
    ends_with_line_break = code.endswith(("\n", "\r"))
    line_count = len(lines) - 1 if ends_with_line_break else len(lines)
    commented = "\n".join("// " + lines[index] for index in range(line_count))
    if ends_with_line_break:
        commented += "\n"
    return commented


def ordered_unique(values: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def build_cross_file_context(similar_code: list[str]) -> str:
    parts = [
        "// Global Declarations:\n",
        "no related global declarations\n",
        "// Similar Code Snippets:\n",
    ]
    if not similar_code:
        parts.append("no related similar code snippets\n")
    else:
        parts.extend(
            (
                "// skeleton 1:\n",
                comment_similar_code(similar_code[0]),
                "\n\n",
            )
        )
    return "".join(parts)


def as_string(value: Any) -> str:
    return value if isinstance(value, str) else ""


def as_list(value: Any, field: str, source: Path) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"{source}: field {field!r} must be a JSON array")
    return value


def build_task3_prompt(sample: dict[str, Any], target_path: str, source: Path) -> str:
    skeleton_candidates: list[str] = []
    for item in as_list(sample.get("target_files"), "target_files", source):
        if not isinstance(item, dict):
            raise ValueError(f"{source}: target_files entries must be JSON objects")
        if as_string(item.get("source_path")) == target_path:
            skeleton_candidates.append(as_string(item.get("skeleton")))

    for item in as_list(sample.get("skeletons"), "skeletons", source):
        skeleton_candidates.append(as_string(item))

    skeletons = ordered_unique(skeleton_candidates)
    cross = build_cross_file_context(skeletons)

    prefix = [
        f"// the file path is: {target_path}\n",
        "// the code file is written by Java\n",
        "// Full-file code completion. Produce the complete Java source for this file.\n",
        "// Natural-language requirement:\n",
    ]
    requirement = as_string(sample.get("requirement"))
    for line in split_java_lines(requirement):
        prefix.append(f"// {line}\n")
    prefix.append("// The complete Java source begins below:\n")

    return (
        cross
        + "// Full-file Completion Task\n\n"
        + "<s>▁<AIX-SPAN-PRE>▁<AIX-SPAN-POST>"
        + "▁<AIX-SPAN-MIDDLE>"
        + "".join(prefix)
    )


def is_escaped(value: str, index: int) -> bool:
    backslashes = 0
    cursor = index - 1
    while cursor >= 0 and value[cursor] == "\\":
        backslashes += 1
        cursor -= 1
    return backslashes % 2 == 1


def strip_java_comments(source: str) -> str:
    """Remove Java comments without changing comment-like text in literals."""

    NORMAL = 0
    STRING = 1
    CHARACTER = 2
    TEXT_BLOCK = 3
    LINE_COMMENT = 4
    BLOCK_COMMENT = 5

    output: list[str] = []
    state = NORMAL
    index = 0
    length = len(source)
    block_had_line_break = False

    while index < length:
        char = source[index]
        following = source[index + 1] if index + 1 < length else ""

        if state == NORMAL:
            if source.startswith('"""', index):
                output.append('"""')
                index += 3
                state = TEXT_BLOCK
            elif char == '"':
                output.append(char)
                index += 1
                state = STRING
            elif char == "'":
                output.append(char)
                index += 1
                state = CHARACTER
            elif char == "/" and following == "/":
                index += 2
                state = LINE_COMMENT
            elif char == "/" and following == "*":
                index += 2
                block_had_line_break = False
                state = BLOCK_COMMENT
            else:
                output.append(char)
                index += 1

        elif state == STRING:
            output.append(char)
            index += 1
            if char == '"' and not is_escaped(source, index - 1):
                state = NORMAL

        elif state == CHARACTER:
            output.append(char)
            index += 1
            if char == "'" and not is_escaped(source, index - 1):
                state = NORMAL

        elif state == TEXT_BLOCK:
            if source.startswith('"""', index) and not is_escaped(source, index):
                output.append('"""')
                index += 3
                state = NORMAL
            else:
                output.append(char)
                index += 1

        elif state == LINE_COMMENT:
            if char == "\r":
                output.append("\r")
                index += 1
                if index < length and source[index] == "\n":
                    output.append("\n")
                    index += 1
                state = NORMAL
            elif char in "\n\v\f" or char in "\x1c\x1d\x1e\x85\u2028\u2029":
                output.append(char)
                index += 1
                state = NORMAL
            else:
                index += 1

        elif state == BLOCK_COMMENT:
            if char == "*" and following == "/":
                index += 2
                if (
                    not block_had_line_break
                    and output
                    and not output[-1][-1:].isspace()
                    and index < length
                    and not source[index].isspace()
                ):
                    output.append(" ")
                state = NORMAL
            elif char == "\r":
                output.append("\r")
                block_had_line_break = True
                index += 1
                if index < length and source[index] == "\n":
                    output.append("\n")
                    index += 1
            elif char in "\n\v\f" or char in "\x1c\x1d\x1e\x85\u2028\u2029":
                output.append(char)
                block_had_line_break = True
                index += 1
            else:
                index += 1

    if state == BLOCK_COMMENT:
        raise ValueError("unterminated Java block comment")
    if state == STRING:
        raise ValueError("unterminated Java string literal")
    if state == CHARACTER:
        raise ValueError("unterminated Java character literal")
    if state == TEXT_BLOCK:
        raise ValueError("unterminated Java text block")

    cleaned = "".join(output)
    if cleaned.startswith("\ufeff"):
        cleaned = cleaned[1:]
    return cleaned.strip() + "\n"


def safe_child(root: Path, relative: str, description: str) -> Path:
    if not relative:
        raise ValueError(f"empty {description}")
    candidate = (root / relative).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as exception:
        raise ValueError(f"{description} escapes repository root: {relative}") from exception
    return candidate


def discover_task3_files(samples_root: Path) -> list[Path]:
    files = sorted(path for path in samples_root.glob("*/3.json") if path.is_file())
    if (samples_root / "3.json").is_file():
        files.insert(0, samples_root / "3.json")
    return files


def iter_built_records(samples_root: Path, repos_root: Path) -> Iterable[BuiltRecord]:
    task_files = discover_task3_files(samples_root)
    if not task_files:
        raise FileNotFoundError(
            f"no task-3 sample files found under {samples_root} (expected */3.json)"
        )

    for task_file in task_files:
        try:
            document = json.loads(task_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exception:
            raise ValueError(f"cannot read {task_file}: {exception}") from exception
        if not isinstance(document, list):
            raise ValueError(f"{task_file}: top-level JSON value must be an array")

        for sample_index, sample in enumerate(document):
            location = f"{task_file}[{sample_index}]"
            if not isinstance(sample, dict):
                raise ValueError(f"{location}: sample must be a JSON object")
            task = as_string(sample.get("task"))
            if task and task != "difficulty3":
                raise ValueError(f"{location}: expected task=difficulty3, got {task!r}")
            only_sft_train = sample.get("only_sft_train", False)
            if not isinstance(only_sft_train, bool):
                raise ValueError(f"{location}: only_sft_train must be a boolean")

            repo = as_string(sample.get("repo"))
            repo_root = safe_child(repos_root, repo, f"{location} repo")
            if not repo_root.is_dir():
                raise FileNotFoundError(f"{location}: repository does not exist: {repo_root}")

            targets = as_list(sample.get("target_files"), "target_files", task_file)
            if not targets:
                raise ValueError(f"{location}: target_files is empty")

            for target_index, target in enumerate(targets):
                if not isinstance(target, dict):
                    raise ValueError(f"{location}: target_files[{target_index}] must be an object")
                target_path = as_string(target.get("source_path"))
                source_file = safe_child(
                    repo_root, target_path, f"{location} target_files[{target_index}].source_path"
                )
                if not source_file.is_file():
                    raise FileNotFoundError(f"{location}: target file does not exist: {source_file}")

                source_bytes = source_file.read_bytes()
                expected_hash = as_string(target.get("source_sha256"))
                actual_hash = hashlib.sha256(source_bytes).hexdigest()
                if expected_hash and actual_hash != expected_hash:
                    raise ValueError(
                        f"{location}: source hash mismatch for {source_file}; "
                        f"expected {expected_hash}, got {actual_hash}"
                    )
                try:
                    java_source = source_bytes.decode("utf-8")
                except UnicodeDecodeError as exception:
                    raise ValueError(f"{location}: target is not UTF-8: {source_file}") from exception

                sample_skeletons = as_list(sample.get("skeletons"), "skeletons", task_file)
                if not sample_skeletons:
                    raise ValueError(f"{location}: skeletons is empty")
                original_id = as_string(sample.get("id"))
                if not original_id:
                    raise ValueError(f"{location}: id is empty")

                yield BuiltRecord(
                    repository=repo,
                    sample_key=(str(task_file), sample_index),
                    record={
                        "id": f"{repo}_{original_id}",
                        "prompt": build_task3_prompt(sample, target_path, task_file),
                        # "source": strip_java_comments(java_source),
                        "source": java_source,  # 暂时先不去掉注释了
                        "skeleton": as_string(sample_skeletons[0]),
                        "detailed_requirement": as_string(sample.get("requirement")),
                        "only_sft_train": only_sft_train,
                    },
                )


def iter_records(samples_root: Path, repos_root: Path) -> Iterable[dict[str, Any]]:
    """Yield the generated records without the split metadata."""

    for built_record in iter_built_records(samples_root, repos_root):
        yield built_record.record


def choose_eval_sample_keys(
    built_records: list[BuiltRecord],
) -> set[tuple[str, tuple[str, int]]]:
    """Choose a deterministic per-repository eval subset at original-sample granularity."""

    samples_by_repository: dict[str, dict[tuple[str, int], bool]] = {}
    for built_record in built_records:
        repository_samples = samples_by_repository.setdefault(built_record.repository, {})
        only_sft_train = built_record.record["only_sft_train"]
        previous_value = repository_samples.setdefault(
            built_record.sample_key, only_sft_train
        )
        if previous_value != only_sft_train:
            raise ValueError(
                f"inconsistent only_sft_train value for "
                f"{built_record.repository}:{built_record.sample_key}"
            )

    selected: set[tuple[str, tuple[str, int]]] = set()
    for repository, repository_samples in samples_by_repository.items():
        target_count = len(repository_samples) // EVAL_SAMPLE_RATIO_DENOMINATOR
        eligible = [
            sample_key
            for sample_key, only_sft_train in repository_samples.items()
            if not only_sft_train
        ]
        sample_count = min(target_count, len(eligible))
        if sample_count == 0:
            continue

        repository_rng = random.Random(f"{EVAL_RANDOM_SEED}:{repository}")
        selected.update(
            (repository, sample_key)
            for sample_key in repository_rng.sample(eligible, sample_count)
        )
    return selected


def write_jsonl_atomically(records: Iterable[dict[str, Any]], output: Path) -> int:
    output.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=output.parent,
            prefix=f".{output.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            temporary_name = handle.name
            for record in records:
                json.dump(record, handle, ensure_ascii=False, separators=(",", ":"))
                handle.write("\n")
                count += 1
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, output)
        temporary_name = None
    finally:
        if temporary_name is not None:
            Path(temporary_name).unlink(missing_ok=True)
    return count


def run_self_test() -> None:
    source = '''/* license */
class Demo {
  String url = "https://example.test/a/*b*/"; // remove
  char slash = '/';
  String escaped = "\\\"//not-comment";
  String text = """
      // keep
      /* keep */
      """;
  int value = 1 /* inline */ + 2;
  /** docs */
  void run() {}
}
'''
    cleaned = strip_java_comments(source)
    assert "license" not in cleaned
    assert "// remove" not in cleaned
    assert "inline" not in cleaned
    assert "docs" not in cleaned
    assert '"https://example.test/a/*b*/"' in cleaned
    assert "// keep" in cleaned
    assert "/* keep */" in cleaned
    assert re.search(r"1\s+\+\s+2", cleaned)


def parse_arguments(argv: list[str]) -> argparse.Namespace:
    lowcode_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(
        description="Build task-3 SFT JSONL records from existing Java sample files."
    )
    parser.add_argument(
        "samples_root",
        nargs="?",
        type=Path,
        default=lowcode_root / "samples",
        help="sample root containing repository subdirectories and 3.json files "
        "(default: script directory/samples)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=lowcode_root / "sft/sft.jsonl",
        help="output JSONL path (default: script directory/sft/sft.jsonl)",
    )
    parser.add_argument(
        "--train-output",
        type=Path,
        default=lowcode_root / "sft/train.jsonl",
        help="training JSONL path (default: script directory/sft/train.jsonl)",
    )
    parser.add_argument(
        "--eval-output",
        type=Path,
        default=lowcode_root / "sft/eval.jsonl",
        help="evaluation JSONL path (default: script directory/sft/eval.jsonl)",
    )
    parser.add_argument("--self-test", action="store_true", help=argparse.SUPPRESS)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    arguments = parse_arguments(argv)
    if arguments.self_test:
        run_self_test()
        print("Self-test passed.")
        return 0

    lowcode_root = Path(__file__).resolve().parent.parent
    samples_root = arguments.samples_root.expanduser().resolve()
    output = arguments.output.expanduser().resolve()
    train_output = arguments.train_output.expanduser().resolve()
    eval_output = arguments.eval_output.expanduser().resolve()
    repos_root = (lowcode_root / "repos").resolve()

    if not samples_root.is_dir():
        raise FileNotFoundError(f"sample root does not exist: {samples_root}")

    built_records = list(iter_built_records(samples_root, repos_root))
    eval_sample_keys = choose_eval_sample_keys(built_records)
    all_records = [built_record.record for built_record in built_records]
    train_records = [
        built_record.record
        for built_record in built_records
        if (built_record.repository, built_record.sample_key) not in eval_sample_keys
    ]
    eval_records = [
        built_record.record
        for built_record in built_records
        if (built_record.repository, built_record.sample_key) in eval_sample_keys
    ]

    count = write_jsonl_atomically(all_records, output)
    train_count = write_jsonl_atomically(train_records, train_output)
    eval_count = write_jsonl_atomically(eval_records, eval_output)
    print(f"Wrote {count} task-3 SFT records to {output}")
    print(f"Wrote {train_count} training records to {train_output}")
    print(f"Wrote {eval_count} evaluation records to {eval_output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except (FileNotFoundError, ValueError, OSError) as exception:
        print(f"error: {exception}", file=sys.stderr)
        raise SystemExit(1)
