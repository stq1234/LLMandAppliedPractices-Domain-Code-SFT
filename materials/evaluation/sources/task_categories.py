"""Shared task and subtask classification helpers.

The sample files and evaluation reports use the historical ``difficulty1`` /
``difficulty2`` / ``difficulty3`` task identifiers.  The report format now
also exposes five stable subtask identifiers.  This module keeps the mapping
in one place so the counter and the evaluation summary apply the same legacy
fallbacks.
"""

from __future__ import annotations

from collections import Counter
from typing import Any, Mapping


TASK1_LINES = "difficulty1_lines"
TASK1_API = "difficulty1_api"
TASK2_AST = "difficulty2_ast"
TASK2_FUNCTION_BODY = "difficulty2_function_body"
TASK3_FILES = "difficulty3_files"

SUBTASK_KEYS = (
    TASK1_LINES,
    TASK1_API,
    TASK2_AST,
    TASK2_FUNCTION_BODY,
    TASK3_FILES,
)

SUBTASK_TASK_INDEX = {
    TASK1_LINES: 0,
    TASK1_API: 0,
    TASK2_AST: 1,
    TASK2_FUNCTION_BODY: 1,
    TASK3_FILES: 2,
}

SUBTASK_LABELS = {
    TASK1_LINES: "task1 lines",
    TASK1_API: "task1 API",
    TASK2_AST: "task2 internal AST",
    TASK2_FUNCTION_BODY: "task2 complete function body",
    TASK3_FILES: "task3 files",
}


def task_index(value: Any) -> int | None:
    """Return a zero-based task index for common task/tag spellings."""

    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value - 1 if 1 <= value <= 3 else None
    if not isinstance(value, str):
        return None

    normalized = value.strip().lower()
    if normalized in {"1", "2", "3"}:
        return int(normalized) - 1
    for prefix in ("difficulty", "task"):
        if normalized.startswith(prefix):
            suffix = normalized[len(prefix):]
            if suffix in {"1", "2", "3"}:
                return int(suffix) - 1
            return None
    return None


def _nested_sample(record: Mapping[str, Any]) -> Mapping[str, Any] | None:
    sample = record.get("sample")
    return sample if isinstance(sample, Mapping) else None


def _field(record: Mapping[str, Any], name: str) -> Any:
    """Read a result's top-level field, falling back to its embedded sample."""

    value = record.get(name)
    if isinstance(value, str) and value.strip():
        return value.strip()
    sample = _nested_sample(record)
    if sample is not None:
        value = sample.get(name)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _task_index_from_record(record: Mapping[str, Any]) -> int | None:
    for name in ("task", "tag", "difficulty"):
        index = task_index(record.get(name))
        if index is not None:
            return index

    sample = _nested_sample(record)
    if sample is not None:
        for name in ("task", "tag", "difficulty"):
            index = task_index(sample.get(name))
            if index is not None:
                return index
    return None


def classify_record(
    record: Mapping[str, Any],
    fallback_task_index: int | None = None,
) -> str | None:
    """Classify a sample or report result into one stable subtask key.

    The task/difficulty identifier determines the task.  The explicit
    ``subtask`` field is useful as a fallback only when no task identifier is
    available; it cannot move a record across tasks.  Completion markers use
    OR semantics so reports containing only one of the two marker fields are
    still classified consistently with the Java producer.  For old reports,
    missing task1 annotations mean API and missing task2 annotations mean
    internal AST.  In particular, the historical ``block``/``BLOCK`` pair is
    kept in internal AST because those fields alone cannot prove that the
    sample was a complete function body.
    """

    explicit = record.get("subtask")
    explicit_key = (
        explicit.strip()
        if isinstance(explicit, str) and explicit.strip() in SUBTASK_TASK_INDEX
        else None
    )

    index = _task_index_from_record(record)
    if index is None:
        index = fallback_task_index
    if index is None and explicit_key is not None:
        index = SUBTASK_TASK_INDEX[explicit_key]
    if index is None:
        return None

    completion_kind = _field(record, "completion_kind")
    ast_node_kind = _field(record, "ast_node_kind")
    if index == 0:
        if completion_kind == "lines" or ast_node_kind == "LINE_RANGE":
            return TASK1_LINES
        # This is both the current task1 API marker and the compatibility
        # fallback for historical samples with no annotations.
        return TASK1_API
    if index == 1:
        if completion_kind == "function_body" or ast_node_kind == "FUNCTION_BODY":
            return TASK2_FUNCTION_BODY
        return TASK2_AST
    if index == 2:
        return TASK3_FILES
    return None


def count_subtasks(
    records: list[Mapping[str, Any]],
    fallback_task_index: int | None = None,
) -> Counter[str]:
    """Count records by stable subtask key, retaining zero-valued keys."""

    counts: Counter[str] = Counter({key: 0 for key in SUBTASK_KEYS})
    for record in records:
        key = classify_record(record, fallback_task_index)
        if key is not None:
            counts[key] += 1
    return counts


def subtask_task_index(subtask: str) -> int | None:
    """Return the zero-based parent task index for a subtask key."""

    return SUBTASK_TASK_INDEX.get(subtask)
