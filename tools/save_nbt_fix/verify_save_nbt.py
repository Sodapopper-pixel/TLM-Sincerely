# -*- coding: utf-8 -*-
"""验证修复结果：MCA 完整性 + maid_backups 修复正确性 + 与备份对比无数据损坏。"""
import io
import gzip
import os
import sys
import hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mca_common import (  # noqa: E402
    iter_mca_chunks, decompress_payload, parse_nbt, walk_containers,
    tag_type_name, MAID_ID, CHAIR_ID, GOMOKU_ID, SCHEDULE_KEY,
)
from nbtlib.tag import IntArray, Compound  # noqa: E402

WORLD = os.path.abspath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "saves", "新的世界"))
BACKUP = WORLD + "_backup_before_fix"

failures = []


def check(cond, msg):
    print(("  [PASS] " if cond else "  [FAIL] ") + msg)
    if not cond:
        failures.append(msg)


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


print("=== 1. MCA 完整性验证（逐 chunk 解析 + 与修复前备份对比）===")
mca_pairs = []
for dirpath, _dirs, files in os.walk(WORLD):
    for fn in files:
        if fn.endswith(".mca"):
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, WORLD)
            mca_pairs.append((rel, p, os.path.join(BACKUP, rel)))

all_ok = True
import collections
ent_before, ent_after = collections.Counter(), collections.Counter()
dv_before, dv_after = collections.Counter(), collections.Counter()
n_chunks = 0
for rel, path, bpath in sorted(mca_pairs):
    same = os.path.exists(bpath) and md5(path) == md5(bpath)
    if not same:
        all_ok = False
    # 逐 chunk 解析（同时收集实体统计）
    kind = os.path.basename(os.path.dirname(path))
    try:
        for entry in iter_mca_chunks(path):
            n_chunks += 1
            fobj, root = parse_nbt(decompress_payload(entry.compression, entry.payload))
            dv_after["%s@%s" % (root.get("DataVersion"), kind)] += 1
            if kind == "entities":
                for ent in root.get("Entities", []):
                    if isinstance(ent, Compound):
                        ent_after[str(ent.get("id"))] += 1
    except Exception as e:  # noqa: BLE001
        all_ok = False
        print("  [FAIL] 解析错误 %s: %r" % (rel, e))
    # 备份对照统计
    if os.path.exists(bpath):
        try:
            for entry in iter_mca_chunks(bpath):
                fobj, root = parse_nbt(decompress_payload(entry.compression, entry.payload))
                dv_before["%s@%s" % (root.get("DataVersion"), kind)] += 1
                if kind == "entities":
                    for ent in root.get("Entities", []):
                        if isinstance(ent, Compound):
                            ent_before[str(ent.get("id"))] += 1
        except Exception as e:  # noqa: BLE001
            print("  [FAIL] 备份解析错误 %s: %r" % (rel, e))

check(all_ok, "全部 %d 个 MCA 文件字节级与修复前一致（0 个被重写）且可完整解析" % len(mca_pairs))
check(n_chunks == 4893, "chunk 总数 %d == 修复前 4893" % n_chunks)
check(dict(ent_before) == dict(ent_after) or ent_before == ent_after,
      "实体统计与修复前一致: %s" % dict(ent_after))
check(dv_before == dv_after, "DataVersion 分布与修复前一致: %s" % dv_after)
maid_in_mca = ent_after.get(MAID_ID, 0)
gomoku_in_region = 0
check(maid_in_mca == 0, "MCA 中 maid 实体数 = 0（1.21.1 会话已清除，本次无可修实体）")
check(ent_after.get(CHAIR_ID, 0) == 1, "chair（坐垫）实体仍为 1 个，数据未损坏")

# region 中 gomoku 复查
gomoku_found = 0
for rel, path, bpath in mca_pairs:
    if os.sep + "region" in path:
        for entry in iter_mca_chunks(path):
            fobj, root = parse_nbt(decompress_payload(entry.compression, entry.payload))
            for be in (root.get("block_entities") or []):
                if isinstance(be, Compound) and str(be.get("id")) == GOMOKU_ID:
                    gomoku_found += 1
check(gomoku_found == 0, "region 中 gomoku 方块实体数 = 0（本存档无五子棋，SitId 步骤无对象）")

print("\n=== 2. maid_backups 修复正确性验证 ===")
n_fixed_files = 0
n_schedule_ok = 0
diff_keys_ok = True
bak_base = os.path.join(WORLD, "data", "maid_backups")
for dirpath, _dirs, files in os.walk(bak_base):
    for fn in files:
        if not fn.endswith(".dat") or fn == "index.dat":
            continue
        n_fixed_files += 1
        cur = os.path.join(dirpath, fn)
        old = os.path.join(BACKUP, os.path.relpath(cur, WORLD))
        f_new = parse_nbt(gzip.decompress(open(cur, "rb").read()))[1]
        f_old = parse_nbt(gzip.decompress(open(old, "rb").read()))[1]
        sp = f_new.get(SCHEDULE_KEY)
        ok = isinstance(sp, Compound)
        for key in ("Work", "Idle", "Sleep"):
            v = sp.get(key) if ok else None
            ok = ok and isinstance(v, IntArray) and len(v) == 3
            # 值必须与修复前一致（仅类型转换，不改值）
            old_v = f_old[SCHEDULE_KEY][key]
            if isinstance(old_v, Compound):
                ok = ok and list(v) == [int(old_v["X"]), int(old_v["Y"]), int(old_v["Z"])]
        if not ok:
            failures.append("SchedulePos 异常: " + fn)
            print("  [FAIL] %s" % fn)
        else:
            n_schedule_ok += 1
        # 除 SchedulePos 三个键类型外，其余内容必须完全一致
        old_copy = f_old.copy()
        new_copy = f_new.copy()
        for key in ("Work", "Idle", "Sleep"):
            old_copy[SCHEDULE_KEY][key] = list(new_copy[SCHEDULE_KEY][key])  # 归一化后比较
        if old_copy != new_copy:
            diff_keys_ok = False
            failures.append("存在意外差异: " + fn)
            print("  [FAIL] 意外差异: %s" % fn)

check(n_fixed_files == 23, "maid 备份文件数 = 23")
check(n_schedule_ok == 23, "23 个文件的 MaidSchedulePos.Work/Idle/Sleep 均为 IntArray[len 3] 且值与修复前一致")
check(diff_keys_ok, "所有备份文件除 SchedulePos 类型转换外无任何其他差异")

print("\n=== 3. 修复后备份文件无 {X,Y,Z} BlockPos Compound 残留 ===")


def find_xyz(node, path, out, depth=0):
    if depth > 14:
        return
    if isinstance(node, dict):
        if set(node.keys()) >= {"X", "Y", "Z"} and len(node) == 3:
            try:
                out.append(path)
                return
            except Exception:
                pass
        for k, v in node.items():
            find_xyz(v, path + "." + str(k), out, depth + 1)
    elif isinstance(node, (list, tuple)):
        for i, v in enumerate(node):
            find_xyz(v, path + "[%d]" % i, out, depth + 1)


residual = 0
for dirpath, _dirs, files in os.walk(bak_base):
    for fn in files:
        if not fn.endswith(".dat") or fn == "index.dat":
            continue
        f = parse_nbt(gzip.decompress(open(os.path.join(dirpath, fn), "rb").read()))[1]
        out = []
        find_xyz(f, "", out)
        residual += len(out)
check(residual == 0, "maid_backups 中剩余 {X,Y,Z} Compound 数 = %d" % residual)

print("\n=== 结论 ===")
if failures:
    print("发现 %d 个问题:" % len(failures))
    for m in failures:
        print("  -", m)
    sys.exit(1)
print("全部验证通过。")
