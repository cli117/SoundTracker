#!/usr/bin/env python3
"""
Builds and self-verifies a deterministic STPATCH1 binary delta patch between two signed Android APKs.

Wire format (consumed by ApkDeltaPatcher.kt on Android):
  Header (124 bytes, uncompressed, big-endian):
    0..7    (8s) : ASCII magic b"STPATCH1"
    8..15   (Q)  : source_size (uint64)
    16..47  (32s): source_sha256 (raw 32 bytes)
    48..55  (Q)  : target_size (uint64)
    56..87  (32s): target_sha256 (raw 32 bytes)
    88..91  (I)  : op_count (uint32)
    92..123 (32s): reserved zeros

  Body (byte 124..EOF, GZIP level 9 stream with mtime=0):
    Sequence of `op_count` big-endian instructions:
      - OP_COPY (0x01): uint8(1) + uint64(old_offset) + int32(length)
      - OP_ADD  (0x02): uint8(2) + int32(length) + byte[length]
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import struct
import sys
import time
import zipfile
import zlib
from typing import List, Optional, Tuple

MAGIC = b"STPATCH1"
HEADER_STRUCT = struct.Struct(">8sQ32sQ32sI32s")
OP_COPY = 1
OP_ADD = 2
MAX_OP_CHUNK = 0x40000000  # 1 GiB (< INT32_MAX)


def get_local_data_offset(mv: memoryview, hdr_off: int) -> Optional[int]:
    if hdr_off < 0 or hdr_off + 30 > len(mv):
        return None
    if mv[hdr_off : hdr_off + 4] != b"PK\x03\x04":
        return None
    fname_len, extra_len = struct.unpack_from("<HH", mv, hdr_off + 26)
    data_off = hdr_off + 30 + fname_len + extra_len
    return data_off if data_off <= len(mv) else None


def extend_match(
    old_mv: memoryview,
    new_mv: memoryview,
    old_off: int,
    new_off: int,
    match_len: int,
    new_min: int,
    new_max: int,
) -> Tuple[int, int, int]:
    old_len = len(old_mv)

    # Extend backward in coarse-to-fine steps
    for step in (4096, 64, 1):
        while old_off >= step and new_off - step >= new_min:
            if old_mv[old_off - step : old_off] == new_mv[new_off - step : new_off]:
                old_off -= step
                new_off -= step
                match_len += step
            else:
                break

    # Extend forward in coarse-to-fine steps
    for step in (65536, 4096, 64, 1):
        while (
            old_off + match_len + step <= old_len
            and new_off + match_len + step <= new_max
        ):
            o_end = old_off + match_len
            n_end = new_off + match_len
            if old_mv[o_end : o_end + step] == new_mv[n_end : n_end + step]:
                match_len += step
            else:
                break

    return old_off, new_off, match_len


def stage1_zip_entry_matches(
    old_path: str,
    new_path: str,
    old_mv: memoryview,
    new_mv: memoryview,
) -> Tuple[List[Tuple[int, int, int]], List[Tuple[int, int]]]:
    """
    Returns:
      - matched_spans: sorted non-overlapping (new_start, new_end, old_start)
      - candidate_old_ranges: old byte ranges likely useful for Stage 2 sub-block diffing
    """
    old_len = len(old_mv)
    new_len = len(new_mv)
    matched_spans: List[Tuple[int, int, int]] = []
    candidate_old_ranges: List[Tuple[int, int]] = []

    try:
        zf_old = zipfile.ZipFile(old_path, "r")
        zf_new = zipfile.ZipFile(new_path, "r")
    except zipfile.BadZipFile:
        return [], [(0, old_len)]

    with zf_old, zf_new:
        old_infos = sorted(zf_old.infolist(), key=lambda z: z.header_offset)
        new_infos = sorted(zf_new.infolist(), key=lambda z: z.header_offset)

        old_by_name = {info.filename: info for info in old_infos}
        old_by_sig = {}
        max_old_entry_end = 0
        for info in old_infos:
            sig = (info.CRC, info.compress_size, info.file_size)
            old_by_sig.setdefault(sig, info)
            d_off = get_local_data_offset(old_mv, info.header_offset)
            if d_off is not None:
                max_old_entry_end = max(max_old_entry_end, d_off + info.compress_size)

        if max_old_entry_end < old_len:
            # Includes APK Signing Block + ZIP Central Directory + EOCD
            candidate_old_ranges.append((max_old_entry_end, old_len))
        if old_infos and old_infos[0].header_offset > 0:
            candidate_old_ranges.append((0, old_infos[0].header_offset))

        cursor = 0
        for ninfo in new_infos:
            if ninfo.compress_size <= 0:
                continue
            new_data_off = get_local_data_offset(new_mv, ninfo.header_offset)
            if new_data_off is None:
                continue
            new_data_end = new_data_off + ninfo.compress_size
            if new_data_end <= cursor or new_data_end > new_len:
                continue

            oinfo = old_by_name.get(ninfo.filename)
            nsig = (ninfo.CRC, ninfo.compress_size, ninfo.file_size)
            same_payload = (
                oinfo is not None
                and (oinfo.CRC, oinfo.compress_size, oinfo.file_size) == nsig
            )
            if not same_payload:
                if oinfo is not None:
                    o_doff = get_local_data_offset(old_mv, oinfo.header_offset)
                    if o_doff is not None:
                        candidate_old_ranges.append(
                            (oinfo.header_offset, min(old_len, o_doff + oinfo.compress_size))
                        )
                oinfo = old_by_sig.get(nsig) if ninfo.compress_size >= 64 else None

            if oinfo is None:
                continue

            old_data_off = get_local_data_offset(old_mv, oinfo.header_offset)
            if old_data_off is None:
                continue

            trim = max(0, cursor - new_data_off)
            n_off = new_data_off + trim
            o_off = old_data_off + trim
            rem = ninfo.compress_size - trim
            if rem <= 0 or o_off + rem > old_len:
                continue

            if old_mv[o_off : o_off + rem] == new_mv[n_off : n_off + rem]:
                m_old, m_new, m_len = extend_match(
                    old_mv, new_mv, o_off, n_off, rem, cursor, new_len
                )
                matched_spans.append((m_new, m_new + m_len, m_old))
                cursor = m_new + m_len

    return matched_spans, candidate_old_ranges


def merge_intervals(intervals: List[Tuple[int, int]]) -> List[Tuple[int, int]]:
    valid = sorted((max(0, s), e) for s, e in intervals if e > s)
    if not valid:
        return []
    merged = [valid[0]]
    for s, e in valid[1:]:
        ps, pe = merged[-1]
        if s <= pe:
            merged[-1] = (ps, max(pe, e))
        else:
            merged.append((s, e))
    return merged


def stage2_subblock_matches(
    old_mv: memoryview,
    new_mv: memoryview,
    stage1_spans: List[Tuple[int, int, int]],
    candidate_old_ranges: List[Tuple[int, int]],
) -> List[Tuple[int, int, int]]:
    """
    Deduplicates remaining unmatched gaps in `new_mv` (such as resources.arsc, Central Directory,
    and unchanged sections of modified uncompressed .so / .dex entries) using 64-byte block hashing.
    """
    BLOCK = 64
    new_len = len(new_mv)
    old_len = len(old_mv)

    # Identify unmatched gaps in new_mv
    gaps: List[Tuple[int, int]] = []
    pos = 0
    for ns, ne, _ in stage1_spans:
        if ns > pos:
            gaps.append((pos, ns))
        pos = ne
    if pos < new_len:
        gaps.append((pos, new_len))

    significant_gaps = [g for g in gaps if (g[1] - g[0]) >= BLOCK]
    if not significant_gaps:
        return stage1_spans

    # Also include small header regions around entries if candidate_old_ranges is empty
    if not candidate_old_ranges:
        candidate_old_ranges = [(0, old_len)]
    merged_old = merge_intervals(
        [(s, min(old_len, e)) for s, e in candidate_old_ranges]
    )
    total_cand_bytes = sum(e - s for s, e in merged_old)
    if total_cand_bytes < BLOCK:
        return stage1_spans

    stride = max(16, total_cand_bytes // 400_000)
    crc_index: dict[int, int] = {}
    crc32 = zlib.crc32
    for os_off, oe_off in merged_old:
        limit = oe_off - BLOCK
        off = os_off
        while off <= limit:
            h = crc32(old_mv[off : off + BLOCK])
            crc_index.setdefault(h, off)
            off += stride

    extra_spans: List[Tuple[int, int, int]] = []
    for gap_start, gap_end in significant_gaps:
        floor = gap_start
        cur = gap_start
        limit = gap_end - BLOCK
        while cur <= limit:
            h = crc32(new_mv[cur : cur + BLOCK])
            cand_old = crc_index.get(h)
            if cand_old is not None and old_mv[cand_old : cand_old + BLOCK] == new_mv[cur : cur + BLOCK]:
                m_old, m_new, m_len = extend_match(
                    old_mv, new_mv, cand_old, cur, BLOCK, floor, gap_end
                )
                extra_spans.append((m_new, m_new + m_len, m_old))
                floor = m_new + m_len
                cur = floor
            else:
                cur += 1

    all_spans = sorted(stage1_spans + extra_spans, key=lambda x: x[0])
    return all_spans


def build_operations(
    new_len: int,
    matched_spans: List[Tuple[int, int, int]],
) -> List[Tuple[int, int, int]]:
    """
    Converts sorted non-overlapping matched_spans into a complete sequence of coalesced ops:
      - (OP_COPY, old_offset, length)
      - (OP_ADD,  new_offset, length)
    covering [0, new_len) with every length <= MAX_OP_CHUNK.
    """
    raw_ops: List[Tuple[int, int, int]] = []
    cursor = 0
    for ns, ne, os_off in matched_spans:
        if ns < cursor:
            trim = cursor - ns
            ns += trim
            os_off += trim
        if ne <= ns:
            continue
        if ns > cursor:
            raw_ops.append((OP_ADD, cursor, ns - cursor))
        raw_ops.append((OP_COPY, os_off, ne - ns))
        cursor = ne

    if cursor < new_len:
        raw_ops.append((OP_ADD, cursor, new_len - cursor))

    # Coalesce adjacent contiguous ops and enforce MAX_OP_CHUNK
    coalesced: List[Tuple[int, int, int]] = []
    for op, off, length in raw_ops:
        if length <= 0:
            continue
        if coalesced:
            p_op, p_off, p_len = coalesced[-1]
            if (
                p_op == op
                and p_off + p_len == off
                and p_len + length <= MAX_OP_CHUNK
            ):
                coalesced[-1] = (p_op, p_off, p_len + length)
                continue

        while length > MAX_OP_CHUNK:
            coalesced.append((op, off, MAX_OP_CHUNK))
            off += MAX_OP_CHUNK
            length -= MAX_OP_CHUNK
        if length > 0:
            coalesced.append((op, off, length))

    return coalesced


def verify_patch_file(
    old_data: bytes,
    patch_path: str,
    expected_target_size: int,
    expected_target_sha256: str,
) -> None:
    old_mv = memoryview(old_data)
    with open(patch_path, "rb") as f:
        header_bytes = f.read(HEADER_STRUCT.size)
        if len(header_bytes) != HEADER_STRUCT.size:
            raise RuntimeError("Patch header truncated")
        (
            magic,
            src_size,
            src_sha_raw,
            tgt_size,
            tgt_sha_raw,
            op_count,
            _reserved,
        ) = HEADER_STRUCT.unpack(header_bytes)
        if magic != MAGIC:
            raise RuntimeError("Invalid patch magic")
        if src_size != len(old_data):
            raise RuntimeError("Source size mismatch in patch header")
        if tgt_size != expected_target_size:
            raise RuntimeError("Target size mismatch in patch header")
        if tgt_sha_raw.hex() != expected_target_sha256:
            raise RuntimeError("Target SHA-256 mismatch in patch header")

        decompressed = gzip.decompress(f.read())

    stream = io.BytesIO(decompressed)
    digest = hashlib.sha256()
    written = 0

    for _ in range(op_count):
        op_b = stream.read(1)
        if not op_b:
            raise RuntimeError("Unexpected EOF reading op code")
        op = op_b[0]
        if op == OP_COPY:
            old_off, length = struct.unpack(">Qi", stream.read(12))
            if old_off < 0 or length <= 0 or old_off + length > len(old_mv):
                raise RuntimeError("OP_COPY out of bounds during verification")
            digest.update(old_mv[old_off : old_off + length])
            written += length
        elif op == OP_ADD:
            (length,) = struct.unpack(">i", stream.read(4))
            if length <= 0:
                raise RuntimeError("Invalid OP_ADD length during verification")
            chunk = stream.read(length)
            if len(chunk) != length:
                raise RuntimeError("Unexpected EOF in OP_ADD literal payload")
            digest.update(chunk)
            written += length
        else:
            raise RuntimeError(f"Unknown opcode {op} during verification")

    if written != expected_target_size:
        raise RuntimeError(
            f"Reconstructed length {written} != expected {expected_target_size}"
        )
    actual_sha = digest.hexdigest()
    if actual_sha != expected_target_sha256:
        raise RuntimeError(
            f"Reconstructed SHA-256 {actual_sha} != expected {expected_target_sha256}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate and verify an STPATCH1 incremental APK delta package."
    )
    parser.add_argument("old_apk", help="Path to base (older) signed .apk")
    parser.add_argument("new_apk", help="Path to target (newer) signed .apk")
    parser.add_argument("out_patch", help="Output .stpatch file path")
    parser.add_argument(
        "--json-out",
        default="",
        help="Optional path to write JSON patch metadata",
    )
    args = parser.parse_args()

    t0 = time.monotonic()
    with open(args.old_apk, "rb") as f:
        old_data = f.read()
    with open(args.new_apk, "rb") as f:
        new_data = f.read()

    old_sha = hashlib.sha256(old_data).hexdigest()
    new_sha = hashlib.sha256(new_data).hexdigest()
    old_mv = memoryview(old_data)
    new_mv = memoryview(new_data)

    stage1_spans, cand_old_ranges = stage1_zip_entry_matches(
        args.old_apk, args.new_apk, old_mv, new_mv
    )
    all_spans = stage2_subblock_matches(
        old_mv, new_mv, stage1_spans, cand_old_ranges
    )
    ops = build_operations(len(new_data), all_spans)

    copied_bytes = sum(length for op, _, length in ops if op == OP_COPY)
    added_bytes = sum(length for op, _, length in ops if op == OP_ADD)
    assert copied_bytes + added_bytes == len(new_data)

    raw_payload = io.BytesIO()
    for op, off, length in ops:
        if op == OP_COPY:
            raw_payload.write(struct.pack(">BQi", OP_COPY, off, length))
        else:
            raw_payload.write(struct.pack(">Bi", OP_ADD, length))
            raw_payload.write(new_mv[off : off + length])

    compressed_payload = gzip.compress(
        raw_payload.getvalue(), compresslevel=9, mtime=0
    )

    header = HEADER_STRUCT.pack(
        MAGIC,
        len(old_data),
        bytes.fromhex(old_sha),
        len(new_data),
        bytes.fromhex(new_sha),
        len(ops),
        b"\x00" * 32,
    )

    os.makedirs(os.path.dirname(os.path.abspath(args.out_patch)), exist_ok=True)
    with open(args.out_patch, "wb") as f:
        f.write(header)
        f.write(compressed_payload)

    # Mandatory end-to-end replay verification
    verify_patch_file(old_data, args.out_patch, len(new_data), new_sha)

    with open(args.out_patch, "rb") as f:
        patch_bytes = f.read()
    patch_size = len(patch_bytes)
    patch_sha = hashlib.sha256(patch_bytes).hexdigest()
    elapsed = time.monotonic() - t0

    ratio_pct = (patch_size / len(new_data) * 100.0) if new_data else 0.0
    saved_mb = (len(new_data) - patch_size) / (1024.0 * 1024.0)
    print(
        f"[STPATCH1] Verified OK in {elapsed:.2f}s: "
        f"{patch_size / (1024.0 * 1024.0):.2f} MiB ({ratio_pct:.2f}% of full APK, "
        f"saved {saved_mb:.2f} MiB, ops={len(ops)}, copied={copied_bytes}/{len(new_data)} bytes)"
    )

    if args.json_out:
        meta = {
            "sourceSizeBytes": len(old_data),
            "sourceSha256": old_sha,
            "targetSizeBytes": len(new_data),
            "targetSha256": new_sha,
            "patchSizeBytes": patch_size,
            "patchSha256": patch_sha,
            "opCount": len(ops),
            "copiedBytes": copied_bytes,
            "addedBytes": added_bytes,
        }
        os.makedirs(os.path.dirname(os.path.abspath(args.json_out)), exist_ok=True)
        with open(args.json_out, "w", encoding="utf-8") as jf:
            json.dump(meta, jf, indent=2)

    return 0


if __name__ == "__main__":
    sys.exit(main())
