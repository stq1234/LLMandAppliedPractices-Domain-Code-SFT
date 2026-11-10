"""Shared presentation and additive metrics for the five evaluation subtasks."""

from collections import Counter
from decimal import Decimal, ROUND_HALF_UP

from task_categories import SUBTASK_KEYS

DOMAINS = ("企业信息化", "智慧物流", "智慧城市")
VALID_STATUSES = {"passed", "failed", "error"}
TASK_SUBTASKS = {1: SUBTASK_KEYS[:2], 2: SUBTASK_KEYS[2:4], 3: SUBTASK_KEYS[4:]}
REPORT_NAMES = dict(zip(SUBTASK_KEYS, ("snippet", "api", "completion", "generation", "synthesis")))


def task1_character_metrics(record):
    """Read valid additive character counts; never average rounded percentages."""
    total, matched = record.get("total_characters"), record.get("matched_characters")
    if (isinstance(total, bool) or not isinstance(total, int) or total < 0
            or isinstance(matched, bool) or not isinstance(matched, int)
            or matched < 0 or matched > total):
        return None
    return total, matched


def add_character_metrics(counts, record):
    metrics = task1_character_metrics(record)
    if metrics is None:
        counts["pending_metrics"] += 1
        return
    total, matched = metrics
    counts["total_characters"] += total
    counts["matched_characters"] += matched


def format_percentage(numerator, denominator):
    """Use conventional half-up rounding, matching the Java report formatter."""
    value = Decimal(numerator) * 100 / Decimal(denominator)
    return f"{value.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)}%"


def format_metric(subtask, counts):
    if subtask in TASK_SUBTASKS[1]:
        total = counts["total_characters"]
        rate = format_percentage(counts['matched_characters'], total) if total else "N/A"
        return f"CharacterAccuracy={rate}"
    total = sum(counts[status] for status in VALID_STATUSES)
    rate = format_percentage(counts['passed'], total) if total else "0.00%"
    return f"SamplePassRate={rate}"


def print_grouped_summary(grouped):
    """Print three domain rows then the pooled rate for each selected subtask."""
    for subtask in SUBTASK_KEYS:
        if subtask not in grouped:
            continue
        pooled = Counter()
        for domain in DOMAINS:
            counts = grouped[subtask][domain]
            print(f"{REPORT_NAMES[subtask]} / {domain}: {format_metric(subtask, counts)}")
            pooled.update(counts)
        print(f"{REPORT_NAMES[subtask]} / All domains: {format_metric(subtask, pooled)}")
