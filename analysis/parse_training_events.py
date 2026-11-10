#!/usr/bin/env python3
"""Decode scalar metrics from a TensorBoard TFRecord event file.

Run from the repository root:
    python -B "作业5.4_7B领域代码生成SFT实验/analysis/parse_training_events.py"

The parser uses only the Python standard library and writes the per-epoch CSV,
per-step CSV, and an event-only JSON summary next to this script.
"""
from __future__ import annotations

import argparse
import csv
import json
import struct
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

DEFAULT_EVENT = "events.out.tfevents.1788622172.n30010.310.0"
STEPS_PER_EPOCH = 228


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while offset < len(data):
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7
        if shift >= 70:
            raise ValueError("protobuf varint exceeds 64 bits")
    raise ValueError("truncated protobuf varint")


def protobuf_fields(data: bytes) -> Iterable[tuple[int, int, Any]]:
    """Yield (field_number, wire_type, value) for standard protobuf wire types."""
    offset = 0
    while offset < len(data):
        key, offset = read_varint(data, offset)
        number, wire = key >> 3, key & 7
        if number == 0:
            raise ValueError("invalid protobuf field number 0")
        if wire == 0:
            value, offset = read_varint(data, offset)
        elif wire == 1:
            end = offset + 8
            if end > len(data):
                raise ValueError("truncated fixed64 protobuf field")
            value = data[offset:end]
            offset = end
        elif wire == 2:
            length, offset = read_varint(data, offset)
            end = offset + length
            if end > len(data):
                raise ValueError("truncated length-delimited protobuf field")
            value = data[offset:end]
            offset = end
        elif wire == 5:
            end = offset + 4
            if end > len(data):
                raise ValueError("truncated fixed32 protobuf field")
            value = data[offset:end]
            offset = end
        else:
            raise ValueError(f"unsupported protobuf wire type {wire}")
        yield number, wire, value


def decode_event_file(path: Path) -> tuple[dict[str, list[tuple[int, float]]], int, int]:
    """Decode scalar Summary.Value.simple_value records from TFRecord framing.

    Record lengths and payload boundaries are checked. TFRecord CRC fields are
    skipped; local artifact integrity is established separately by the manifest.
    """
    data = path.read_bytes()
    offset = 0
    records = 0
    series: dict[str, list[tuple[int, float]]] = defaultdict(list)

    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError(f"truncated TFRecord header at byte {offset}")
        length = struct.unpack_from("<Q", data, offset)[0]
        offset += 12  # uint64 length plus uint32 length CRC
        end = offset + length
        if end + 4 > len(data):
            raise ValueError(f"truncated TFRecord payload at byte {offset}")
        payload = data[offset:end]
        offset = end + 4  # payload plus uint32 payload CRC
        records += 1

        event_fields = list(protobuf_fields(payload))
        step = next(
            (value for number, wire, value in event_fields if number == 2 and wire == 0),
            None,
        )
        summary = next(
            (value for number, wire, value in event_fields if number == 5 and wire == 2),
            None,
        )
        if step is None or summary is None:
            continue

        for number, wire, value in protobuf_fields(summary):
            if number != 1 or wire != 2:
                continue
            value_fields = list(protobuf_fields(value))
            tag = next(
                (
                    raw.decode("utf-8", "replace")
                    for field, value_wire, raw in value_fields
                    if field == 1 and value_wire == 2
                ),
                None,
            )
            scalar = next(
                (
                    struct.unpack("<f", raw)[0]
                    for field, value_wire, raw in value_fields
                    if field == 2 and value_wire == 5
                ),
                None,
            )
            if tag is not None and scalar is not None:
                series[tag].append((int(step), float(scalar)))

    if offset != len(data):
        raise ValueError(f"event parser stopped at byte {offset} of {len(data)}")
    return series, records, len(data)


def write_metrics(
    series: dict[str, list[tuple[int, float]]],
    event_records: int,
    event_bytes: int,
    event_path: Path,
    steps_per_epoch: int,
    epoch_csv: Path,
    step_csv: Path,
    json_output: Path,
) -> None:
    train = sorted(series.get("train/loss", []))
    eval_losses = sorted(series.get("eval/loss", []))
    if not train or not eval_losses:
        raise ValueError("event file must include train/loss and eval/loss scalars")

    train_steps = [step for step, _ in train]
    if len(set(train_steps)) != len(train_steps):
        raise ValueError("duplicate train/loss step values")
    eval_map = dict(eval_losses)
    learning_rate = dict(series.get("train/learning_rate", []))
    grad_norm = dict(series.get("train/grad_norm", []))

    by_epoch: dict[int, list[float]] = defaultdict(list)
    for step, loss in train:
        epoch = (step - 1) // steps_per_epoch + 1
        by_epoch[epoch].append(loss)

    epoch_rows = []
    for epoch in sorted(by_epoch):
        end_step = epoch * steps_per_epoch
        if end_step not in eval_map:
            raise ValueError(f"missing eval/loss at epoch {epoch}, step {end_step}")
        losses = by_epoch[epoch]
        epoch_rows.append(
            {
                "epoch": epoch,
                "step": end_step,
                "train_loss_mean": sum(losses) / len(losses),
                "eval_loss": eval_map[end_step],
                "train_steps": len(losses),
            }
        )

    epoch_csv.parent.mkdir(parents=True, exist_ok=True)
    step_csv.parent.mkdir(parents=True, exist_ok=True)
    json_output.parent.mkdir(parents=True, exist_ok=True)

    with epoch_csv.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(
            file,
            fieldnames=["epoch", "step", "train_loss_mean", "eval_loss"],
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(
            {key: row[key] for key in ("epoch", "step", "train_loss_mean", "eval_loss")}
            for row in epoch_rows
        )

    with step_csv.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        writer.writerow(["epoch", "step", "train_loss", "eval_loss", "learning_rate", "grad_norm"])
        for step, loss in train:
            writer.writerow(
                [
                    (step - 1) // steps_per_epoch + 1,
                    step,
                    loss,
                    eval_map.get(step, ""),
                    learning_rate.get(step, ""),
                    grad_norm.get(step, ""),
                ]
            )

    event_summary = {
        "event_file": str(event_path),
        "event_bytes": event_bytes,
        "event_records": event_records,
        "steps_per_epoch": steps_per_epoch,
        "scalar_points": {tag: len(values) for tag, values in sorted(series.items())},
        "epochs": epoch_rows,
        "trainer_runtime_seconds": next(
            (value for _, value in series.get("train/train_runtime", [])), None
        ),
        "trainer_reported_train_loss": next(
            (value for _, value in series.get("train/train_loss", [])), None
        ),
        "loss_mean_definition": (
            "Arithmetic mean of per-step train/loss scalar events in each epoch."
        ),
    }
    with json_output.open("w", encoding="utf-8") as file:
        json.dump(event_summary, file, ensure_ascii=False, indent=2)
        file.write("\n")


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--event-file",
        type=Path,
        default=root / "materials" / "training" / DEFAULT_EVENT,
    )
    parser.add_argument("--steps-per-epoch", type=int, default=STEPS_PER_EPOCH)
    parser.add_argument("--epoch-csv", type=Path, default=root / "analysis" / "training_metrics.csv")
    parser.add_argument(
        "--step-csv", type=Path, default=root / "analysis" / "training_step_metrics.csv"
    )
    parser.add_argument(
        "--json-output", type=Path, default=root / "analysis" / "training_event_metrics.json"
    )
    args = parser.parse_args()
    if args.steps_per_epoch <= 0:
        parser.error("--steps-per-epoch must be positive")

    series, records, size = decode_event_file(args.event_file)
    write_metrics(
        series,
        records,
        size,
        args.event_file,
        args.steps_per_epoch,
        args.epoch_csv,
        args.step_csv,
        args.json_output,
    )
    print(
        f"Decoded {records} records ({size} bytes); "
        f"{len(series.get('train/loss', []))} train/loss points and "
        f"{len(series.get('eval/loss', []))} eval/loss points."
    )


if __name__ == "__main__":
    main()
