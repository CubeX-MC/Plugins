#!/usr/bin/env python3
"""
Offline cleaner for Minecraft region files.

This can scan:
- entities/*.mca to remove selected entity ids inside an XZ block range.
- region/*.mca to clear container block entity contents inside an XZ range.

It defaults to dry-run mode and creates backups when --apply is used.
"""

from __future__ import annotations

import argparse
import gzip
import os
import re
import shutil
import struct
import sys
import time
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12

REGION_RE = re.compile(r"^r\.(-?\d+)\.(-?\d+)\.mca$")
DEFAULT_TARGETS = {
    "minecraft:armor_stand",
    "minecraft:chest_minecart",
    "minecraft:hopper_minecart",
    "minecraft:item_frame",
    "minecraft:glow_item_frame",
    "minecraft:minecart",
}


class NbtError(Exception):
    pass


class NbtReader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def read(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise NbtError("Unexpected end of NBT data")
        out = self.data[self.pos : self.pos + n]
        self.pos += n
        return out

    def read_u8(self) -> int:
        return self.read(1)[0]

    def read_i8(self) -> int:
        return struct.unpack(">b", self.read(1))[0]

    def read_i16(self) -> int:
        return struct.unpack(">h", self.read(2))[0]

    def read_u16(self) -> int:
        return struct.unpack(">H", self.read(2))[0]

    def read_i32(self) -> int:
        return struct.unpack(">i", self.read(4))[0]

    def read_i64(self) -> int:
        return struct.unpack(">q", self.read(8))[0]

    def read_f32(self) -> float:
        return struct.unpack(">f", self.read(4))[0]

    def read_f64(self) -> float:
        return struct.unpack(">d", self.read(8))[0]

    def read_string(self) -> str:
        length = self.read_u16()
        return self.read(length).decode("utf-8")

    def read_named_tag(self) -> tuple[int, str, Any]:
        tag_type = self.read_u8()
        if tag_type == TAG_END:
            return TAG_END, "", None
        name = self.read_string()
        return tag_type, name, self.read_payload(tag_type)

    def read_payload(self, tag_type: int) -> Any:
        if tag_type == TAG_BYTE:
            return self.read_i8()
        if tag_type == TAG_SHORT:
            return self.read_i16()
        if tag_type == TAG_INT:
            return self.read_i32()
        if tag_type == TAG_LONG:
            return self.read_i64()
        if tag_type == TAG_FLOAT:
            return self.read_f32()
        if tag_type == TAG_DOUBLE:
            return self.read_f64()
        if tag_type == TAG_BYTE_ARRAY:
            length = self.read_i32()
            if length < 0:
                raise NbtError("Negative byte array length")
            return self.read(length)
        if tag_type == TAG_STRING:
            return self.read_string()
        if tag_type == TAG_LIST:
            elem_type = self.read_u8()
            length = self.read_i32()
            if length < 0:
                raise NbtError("Negative list length")
            return elem_type, [self.read_payload(elem_type) for _ in range(length)]
        if tag_type == TAG_COMPOUND:
            items: list[tuple[str, int, Any]] = []
            while True:
                child_type = self.read_u8()
                if child_type == TAG_END:
                    return items
                child_name = self.read_string()
                items.append((child_name, child_type, self.read_payload(child_type)))
        if tag_type == TAG_INT_ARRAY:
            length = self.read_i32()
            if length < 0:
                raise NbtError("Negative int array length")
            return [self.read_i32() for _ in range(length)]
        if tag_type == TAG_LONG_ARRAY:
            length = self.read_i32()
            if length < 0:
                raise NbtError("Negative long array length")
            return [self.read_i64() for _ in range(length)]
        raise NbtError(f"Unsupported NBT tag type {tag_type}")


class NbtWriter:
    def __init__(self):
        self.parts: list[bytes] = []

    def write(self, data: bytes) -> None:
        self.parts.append(data)

    def write_u8(self, value: int) -> None:
        self.write(struct.pack(">B", value))

    def write_i8(self, value: int) -> None:
        self.write(struct.pack(">b", value))

    def write_i16(self, value: int) -> None:
        self.write(struct.pack(">h", value))

    def write_u16(self, value: int) -> None:
        self.write(struct.pack(">H", value))

    def write_i32(self, value: int) -> None:
        self.write(struct.pack(">i", value))

    def write_i64(self, value: int) -> None:
        self.write(struct.pack(">q", value))

    def write_f32(self, value: float) -> None:
        self.write(struct.pack(">f", value))

    def write_f64(self, value: float) -> None:
        self.write(struct.pack(">d", value))

    def write_string(self, value: str) -> None:
        encoded = value.encode("utf-8")
        if len(encoded) > 65535:
            raise NbtError("NBT string too long")
        self.write_u16(len(encoded))
        self.write(encoded)

    def write_named_tag(self, tag_type: int, name: str, value: Any) -> None:
        self.write_u8(tag_type)
        if tag_type == TAG_END:
            return
        self.write_string(name)
        self.write_payload(tag_type, value)

    def write_payload(self, tag_type: int, value: Any) -> None:
        if tag_type == TAG_BYTE:
            self.write_i8(value)
        elif tag_type == TAG_SHORT:
            self.write_i16(value)
        elif tag_type == TAG_INT:
            self.write_i32(value)
        elif tag_type == TAG_LONG:
            self.write_i64(value)
        elif tag_type == TAG_FLOAT:
            self.write_f32(value)
        elif tag_type == TAG_DOUBLE:
            self.write_f64(value)
        elif tag_type == TAG_BYTE_ARRAY:
            self.write_i32(len(value))
            self.write(value)
        elif tag_type == TAG_STRING:
            self.write_string(value)
        elif tag_type == TAG_LIST:
            elem_type, values = value
            self.write_u8(elem_type)
            self.write_i32(len(values))
            for item in values:
                self.write_payload(elem_type, item)
        elif tag_type == TAG_COMPOUND:
            for child_name, child_type, child_value in value:
                self.write_named_tag(child_type, child_name, child_value)
            self.write_u8(TAG_END)
        elif tag_type == TAG_INT_ARRAY:
            self.write_i32(len(value))
            for item in value:
                self.write_i32(item)
        elif tag_type == TAG_LONG_ARRAY:
            self.write_i32(len(value))
            for item in value:
                self.write_i64(item)
        else:
            raise NbtError(f"Unsupported NBT tag type {tag_type}")

    def finish(self) -> bytes:
        return b"".join(self.parts)


@dataclass
class ChunkRecord:
    index: int
    sector_offset: int
    sector_count: int
    timestamp: int
    record: bytes
    compression: int
    modified: bool = False
    removed: int = 0


@dataclass
class FileResult:
    file: Path
    chunks: int = 0
    removed: int = 0
    changed_containers: int = 0
    removed_loot_tables: int = 0
    modified_chunks: int = 0
    warnings: int = 0
    skipped_unsupported: int = 0
    unsafe: bool = False


def normalize_entity_id(value: str) -> str:
    key = value.strip().lower()
    key = key.replace(" ", "_")
    if ":" not in key:
        key = "minecraft:" + key
    return key


def region_overlaps(rx: int, rz: int, min_x: float, max_x: float, min_z: float, max_z: float) -> bool:
    region_min_x = rx * 512
    region_max_x = region_min_x + 511
    region_min_z = rz * 512
    region_max_z = region_min_z + 511
    return not (
        region_max_x < min_x
        or region_min_x > max_x
        or region_max_z < min_z
        or region_min_z > max_z
    )


def decompress_payload(compression: int, payload: bytes) -> bytes | None:
    if compression & 0x80:
        return None
    compression = compression & 0x7F
    if compression == 1:
        return gzip.decompress(payload)
    if compression == 2:
        return zlib.decompress(payload)
    if compression == 3:
        return payload
    if compression == 4:
        try:
            import lz4.block  # type: ignore
        except Exception:
            return None
        return lz4.block.decompress(payload)
    return None


def compress_payload(compression: int, payload: bytes) -> bytes | None:
    if compression & 0x80:
        return None
    compression = compression & 0x7F
    if compression == 1:
        return gzip.compress(payload)
    if compression == 2:
        return zlib.compress(payload)
    if compression == 3:
        return payload
    if compression == 4:
        try:
            import lz4.block  # type: ignore
        except Exception:
            return None
        return lz4.block.compress(payload)
    return None


def compound_get(compound: list[tuple[str, int, Any]], name: str) -> tuple[int, Any] | None:
    for child_name, child_type, child_value in compound:
        if child_name == name:
            return child_type, child_value
    return None


def compound_remove(compound: list[tuple[str, int, Any]], name: str) -> int:
    before = len(compound)
    compound[:] = [item for item in compound if item[0] != name]
    return before - len(compound)


def compound_set(compound: list[tuple[str, int, Any]], name: str, tag_type: int, value: Any) -> None:
    for i, (child_name, _, _) in enumerate(compound):
        if child_name == name:
            compound[i] = (name, tag_type, value)
            return
    compound.append((name, tag_type, value))


def entity_id(entity: list[tuple[str, int, Any]]) -> str | None:
    found = compound_get(entity, "id")
    if not found or found[0] != TAG_STRING:
        return None
    return str(found[1]).lower()


def entity_pos(entity: list[tuple[str, int, Any]]) -> tuple[float, float] | None:
    found = compound_get(entity, "Pos")
    if not found or found[0] != TAG_LIST:
        return None
    elem_type, values = found[1]
    if elem_type not in (TAG_DOUBLE, TAG_FLOAT) or len(values) < 3:
        return None
    return float(values[0]), float(values[2])


def block_entity_id(block_entity: list[tuple[str, int, Any]]) -> str | None:
    found = compound_get(block_entity, "id")
    if not found or found[0] != TAG_STRING:
        return None
    return normalize_entity_id(str(found[1]))


def block_entity_pos(block_entity: list[tuple[str, int, Any]]) -> tuple[int, int] | None:
    x = compound_get(block_entity, "x")
    z = compound_get(block_entity, "z")
    if not x or not z or x[0] != TAG_INT or z[0] != TAG_INT:
        return None
    return int(x[1]), int(z[1])


def remove_entities_from_nbt(
    nbt_data: bytes,
    targets: set[str],
    min_x: float,
    max_x: float,
    min_z: float,
    max_z: float,
) -> tuple[bytes, int]:
    reader = NbtReader(nbt_data)
    root_type, root_name, root_value = reader.read_named_tag()
    if root_type != TAG_COMPOUND:
        return nbt_data, 0

    found = compound_get(root_value, "Entities")
    if not found or found[0] != TAG_LIST:
        return nbt_data, 0

    elem_type, entities = found[1]
    if elem_type != TAG_COMPOUND:
        return nbt_data, 0

    kept = []
    removed = 0
    for entity in entities:
        eid = entity_id(entity)
        pos = entity_pos(entity)
        in_range = pos is not None and min_x <= pos[0] <= max_x and min_z <= pos[1] <= max_z
        if eid in targets and in_range:
            removed += 1
        else:
            kept.append(entity)

    if removed == 0:
        return nbt_data, 0

    compound_set(root_value, "Entities", TAG_LIST, (TAG_COMPOUND, kept))
    writer = NbtWriter()
    writer.write_named_tag(root_type, root_name, root_value)
    return writer.finish(), removed


def clear_container_contents_from_nbt(
    nbt_data: bytes,
    container_ids: set[str],
    min_x: float,
    max_x: float,
    min_z: float,
    max_z: float,
    clear_loot_tables: bool,
) -> tuple[bytes, int, int, int]:
    reader = NbtReader(nbt_data)
    root_type, root_name, root_value = reader.read_named_tag()
    if root_type != TAG_COMPOUND:
        return nbt_data, 0, 0, 0

    lists: list[list[Any]] = []
    direct = compound_get(root_value, "block_entities")
    if direct and direct[0] == TAG_LIST and direct[1][0] == TAG_COMPOUND:
        lists.append(direct[1][1])

    legacy_level = compound_get(root_value, "Level")
    if legacy_level and legacy_level[0] == TAG_COMPOUND:
        legacy_tile_entities = compound_get(legacy_level[1], "TileEntities")
        if legacy_tile_entities and legacy_tile_entities[0] == TAG_LIST and legacy_tile_entities[1][0] == TAG_COMPOUND:
            lists.append(legacy_tile_entities[1][1])

    if not lists:
        return nbt_data, 0, 0, 0

    removed_items = 0
    changed_containers = 0
    removed_loot_tables = 0

    for block_entities in lists:
        for block_entity in block_entities:
            pos = block_entity_pos(block_entity)
            if pos is None:
                continue
            if not (min_x <= pos[0] <= max_x and min_z <= pos[1] <= max_z):
                continue

            be_id = block_entity_id(block_entity)
            if container_ids and be_id not in container_ids:
                continue

            items = compound_get(block_entity, "Items")
            has_items = items is not None and items[0] == TAG_LIST
            has_loot_table = compound_get(block_entity, "LootTable") is not None
            if not has_items and not (clear_loot_tables and has_loot_table):
                continue

            changed = False
            if has_items:
                elem_type, values = items[1]
                removed_items += len(values)
                compound_set(block_entity, "Items", TAG_LIST, (elem_type, []))
                changed = True

            if clear_loot_tables:
                removed_loot_tables += compound_remove(block_entity, "LootTable")
                compound_remove(block_entity, "LootTableSeed")
                changed = True

            if changed:
                changed_containers += 1

    if changed_containers == 0:
        return nbt_data, 0, 0, 0

    writer = NbtWriter()
    writer.write_named_tag(root_type, root_name, root_value)
    return writer.finish(), removed_items, changed_containers, removed_loot_tables


def read_chunk_records(region_data: bytes, result: FileResult) -> list[ChunkRecord | None]:
    if len(region_data) < 8192:
        result.unsafe = True
        result.warnings += 1
        return [None] * 1024

    records: list[ChunkRecord | None] = [None] * 1024
    for i in range(1024):
        offset_entry = int.from_bytes(region_data[i * 4 : i * 4 + 4], "big")
        sector_offset = offset_entry >> 8
        sector_count = offset_entry & 0xFF
        timestamp = int.from_bytes(region_data[4096 + i * 4 : 4096 + i * 4 + 4], "big")

        if sector_offset == 0 or sector_count == 0:
            continue

        start = sector_offset * 4096
        max_end = start + sector_count * 4096
        if start + 5 > len(region_data) or max_end > len(region_data):
            result.unsafe = True
            result.warnings += 1
            continue

        length = int.from_bytes(region_data[start : start + 4], "big")
        if length <= 1 or start + 4 + length > max_end:
            result.unsafe = True
            result.warnings += 1
            continue

        compression = region_data[start + 4]
        record = region_data[start : start + 4 + length]
        records[i] = ChunkRecord(i, sector_offset, sector_count, timestamp, record, compression)
        result.chunks += 1

    return records


def build_region_file(records: Iterable[ChunkRecord | None]) -> bytes:
    offset_table = bytearray(4096)
    timestamp_table = bytearray(4096)
    body = bytearray()
    next_sector = 2

    for record in records:
        if record is None:
            continue

        sectors = (len(record.record) + 4095) // 4096
        if sectors > 255:
            raise RuntimeError(f"Chunk {record.index} is too large for inline region storage")

        offset_value = (next_sector << 8) | sectors
        offset_table[record.index * 4 : record.index * 4 + 4] = offset_value.to_bytes(4, "big")
        timestamp = int(time.time()) if record.modified else record.timestamp
        timestamp_table[record.index * 4 : record.index * 4 + 4] = timestamp.to_bytes(4, "big")

        body.extend(record.record)
        body.extend(b"\x00" * (sectors * 4096 - len(record.record)))
        next_sector += sectors

    return bytes(offset_table + timestamp_table + body)


def process_entity_region_file(
    path: Path,
    targets: set[str],
    min_x: float,
    max_x: float,
    min_z: float,
    max_z: float,
    apply: bool,
    backup_dir: Path | None,
) -> FileResult:
    result = FileResult(file=path)
    data = path.read_bytes()
    records = read_chunk_records(data, result)
    if result.unsafe:
        return result

    for record in records:
        if record is None:
            continue

        payload = record.record[5:]
        raw_nbt = decompress_payload(record.compression, payload)
        if raw_nbt is None:
            result.skipped_unsupported += 1
            continue

        try:
            new_nbt, removed = remove_entities_from_nbt(raw_nbt, targets, min_x, max_x, min_z, max_z)
        except Exception:
            result.warnings += 1
            continue

        if removed == 0:
            continue

        compressed = compress_payload(record.compression, new_nbt)
        if compressed is None:
            result.skipped_unsupported += 1
            continue

        compression_byte = bytes([record.compression])
        chunk_length = len(compression_byte) + len(compressed)
        record.record = chunk_length.to_bytes(4, "big") + compression_byte + compressed
        record.modified = True
        record.removed = removed
        result.removed += removed
        result.modified_chunks += 1

    if apply and result.removed > 0:
        if backup_dir is None:
            raise RuntimeError("backup_dir is required when applying changes")
        backup_dir.mkdir(parents=True, exist_ok=True)
        backup_path = backup_dir / path.name
        if not backup_path.exists():
            shutil.copy2(path, backup_path)
        tmp_path = path.with_suffix(path.suffix + ".tmp")
        tmp_path.write_bytes(build_region_file(records))
        os.replace(tmp_path, path)

    return result


def process_container_region_file(
    path: Path,
    container_ids: set[str],
    min_x: float,
    max_x: float,
    min_z: float,
    max_z: float,
    clear_loot_tables: bool,
    apply: bool,
    backup_dir: Path | None,
) -> FileResult:
    result = FileResult(file=path)
    data = path.read_bytes()
    records = read_chunk_records(data, result)
    if result.unsafe:
        return result

    for record in records:
        if record is None:
            continue

        payload = record.record[5:]
        raw_nbt = decompress_payload(record.compression, payload)
        if raw_nbt is None:
            result.skipped_unsupported += 1
            continue

        try:
            new_nbt, removed_items, changed_containers, removed_loot_tables = clear_container_contents_from_nbt(
                raw_nbt,
                container_ids,
                min_x,
                max_x,
                min_z,
                max_z,
                clear_loot_tables,
            )
        except Exception:
            result.warnings += 1
            continue

        if changed_containers == 0:
            continue

        compressed = compress_payload(record.compression, new_nbt)
        if compressed is None:
            result.skipped_unsupported += 1
            continue

        compression_byte = bytes([record.compression])
        chunk_length = len(compression_byte) + len(compressed)
        record.record = chunk_length.to_bytes(4, "big") + compression_byte + compressed
        record.modified = True
        result.removed += removed_items
        result.changed_containers += changed_containers
        result.removed_loot_tables += removed_loot_tables
        result.modified_chunks += 1

    if apply and result.modified_chunks > 0:
        if backup_dir is None:
            raise RuntimeError("backup_dir is required when applying changes")
        backup_dir.mkdir(parents=True, exist_ok=True)
        backup_path = backup_dir / path.name
        if not backup_path.exists():
            shutil.copy2(path, backup_path)
        tmp_path = path.with_suffix(path.suffix + ".tmp")
        tmp_path.write_bytes(build_region_file(records))
        os.replace(tmp_path, path)

    return result


def iter_region_files(region_dir: Path, min_x: float, max_x: float, min_z: float, max_z: float) -> Iterable[Path]:
    for path in sorted(region_dir.glob("r.*.*.mca")):
        match = REGION_RE.match(path.name)
        if not match:
            continue
        rx = int(match.group(1))
        rz = int(match.group(2))
        if region_overlaps(rx, rz, min_x, max_x, min_z, max_z):
            yield path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Offline cleaner for Minecraft entities/*.mca and region/*.mca files.")
    parser.add_argument("--mode", choices=("entities", "containers"), default=None)
    parser.add_argument("--entities-dir", type=Path, help="Path to world/entities")
    parser.add_argument("--region-dir", type=Path, help="Path to world/region")
    parser.add_argument("--min-x", type=float, default=-80000)
    parser.add_argument("--max-x", type=float, default=80000)
    parser.add_argument("--min-z", type=float, default=-80000)
    parser.add_argument("--max-z", type=float, default=80000)
    parser.add_argument(
        "--target",
        action="append",
        default=[],
        help="Entity id to remove. Can be repeated. Defaults to armor stands, item frames, and minecarts.",
    )
    parser.add_argument(
        "--container-id",
        action="append",
        default=[],
        help="Container block entity id to clear. Can be repeated. Defaults to every block entity with Items.",
    )
    parser.add_argument(
        "--keep-loot-tables",
        action="store_true",
        help="Do not remove container LootTable/LootTableSeed tags in container mode.",
    )
    parser.add_argument("--apply", action="store_true", help="Write changes. Without this, only reports removals.")
    parser.add_argument("--backup-dir", type=Path, default=None, help="Backup directory for modified .mca files.")
    parser.add_argument("--progress-every", type=int, default=50)
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    cleaner_mode = args.mode
    if cleaner_mode is None:
        cleaner_mode = "containers" if args.region_dir else "entities"

    scan_dir = args.region_dir if cleaner_mode == "containers" else args.entities_dir
    if scan_dir is None or not scan_dir.is_dir():
        label = "Region" if cleaner_mode == "containers" else "Entities"
        print(f"{label} directory not found: {scan_dir}", file=sys.stderr)
        return 2

    targets = {normalize_entity_id(t) for t in args.target} if args.target else set(DEFAULT_TARGETS)
    container_ids = {normalize_entity_id(t) for t in args.container_id}
    backup_dir = args.backup_dir
    if args.apply and backup_dir is None:
        backup_prefix = "containers-clean-backup-" if cleaner_mode == "containers" else "entities-clean-backup-"
        backup_dir = scan_dir.parent / (backup_prefix + time.strftime("%Y%m%d-%H%M%S"))

    files = list(iter_region_files(scan_dir, args.min_x, args.max_x, args.min_z, args.max_z))
    run_mode = "APPLY" if args.apply else "DRY-RUN"
    print(f"Mode: {run_mode}")
    print(f"Cleaner: {cleaner_mode}")
    print(f"Scan dir: {scan_dir}")
    print(f"Range: x={args.min_x:g}..{args.max_x:g}, z={args.min_z:g}..{args.max_z:g}")
    if cleaner_mode == "containers":
        print("Container ids: " + (", ".join(sorted(container_ids)) if container_ids else "any block entity with Items"))
        print(f"Clear loot tables: {not args.keep_loot_tables}")
    else:
        print(f"Targets: {', '.join(sorted(targets))}")
    print(f"Region files overlapping range: {len(files)}")
    if args.apply:
        print(f"Backups: {backup_dir}")

    total_removed = 0
    total_changed_containers = 0
    total_removed_loot_tables = 0
    total_modified_files = 0
    total_chunks = 0
    total_warnings = 0
    total_unsupported = 0

    for i, path in enumerate(files, start=1):
        if cleaner_mode == "containers":
            result = process_container_region_file(
                path,
                container_ids,
                args.min_x,
                args.max_x,
                args.min_z,
                args.max_z,
                not args.keep_loot_tables,
                args.apply,
                backup_dir,
            )
        else:
            result = process_entity_region_file(
                path,
                targets,
                args.min_x,
                args.max_x,
                args.min_z,
                args.max_z,
                args.apply,
                backup_dir,
            )
        total_removed += result.removed
        total_changed_containers += result.changed_containers
        total_removed_loot_tables += result.removed_loot_tables
        total_chunks += result.chunks
        total_warnings += result.warnings
        total_unsupported += result.skipped_unsupported
        if cleaner_mode == "containers" and result.modified_chunks:
            total_modified_files += 1
            print(
                f"{path.name}: item-stacks={result.removed}, containers={result.changed_containers}, "
                f"loot-tables={result.removed_loot_tables}, chunks={result.modified_chunks}"
            )
        elif result.removed:
            total_modified_files += 1
            print(f"{path.name}: removed={result.removed}, chunks={result.modified_chunks}")
        elif result.unsafe:
            print(f"{path.name}: skipped unsafe/corrupt region metadata")

        if args.progress_every > 0 and i % args.progress_every == 0:
            print(f"Progress: {i}/{len(files)} files, removed={total_removed}")

    print("Done.")
    print(f"Scanned chunks: {total_chunks}")
    print(f"Files with removals: {total_modified_files}")
    if cleaner_mode == "containers":
        print(f"Item stacks removed: {total_removed}")
        print(f"Containers changed: {total_changed_containers}")
        print(f"Loot tables removed: {total_removed_loot_tables}")
    else:
        print(f"Entities removed: {total_removed}")
    print(f"Warnings: {total_warnings}")
    print(f"Unsupported compressed chunks skipped: {total_unsupported}")
    if not args.apply:
        print("Dry-run only. Re-run with --apply to write changes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
