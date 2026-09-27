# -*- coding: utf-8 -*-
"""扫描存档 MCA 文件，统计车万女仆相关 NBT 的现状（修复前体检）。"""
import os
import sys
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mca_common import (  # noqa: E402
    iter_mca_chunks, decompress_payload, parse_nbt, tag_type_name,
    find_typed_entities, walk_containers, MAID_ID, CHAIR_ID, GOMOKU_ID,
    SCHEDULE_KEY,
)

WORLD = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "saves", "新的世界")
WORLD = os.path.abspath(WORLD)

entity_ids = collections.Counter()
maid_stats = {
    "MaidRestrictCenter": collections.Counter(),
    "MaidSchedulePos": collections.Counter(),
    "MaidSchedulePos.Work": collections.Counter(),
    "MaidSchedulePos.Idle": collections.Counter(),
    "MaidSchedulePos.Sleep": collections.Counter(),
}
maid_samples = []
chair_count = 0
chair_passengers = collections.Counter()
gomoku_stats = collections.Counter()
gomoku_samples = []
dataversions = collections.Counter()
chunk_total = 0
bad_chunks = []

# 找所有 .mca（含维度子目录）
mca_files = []
for dirpath, _dirnames, filenames in os.walk(WORLD):
    for fn in filenames:
        if fn.endswith(".mca") or fn.endswith(".mcr"):
            rel = os.path.relpath(os.path.join(dirpath, fn), WORLD)
            mca_files.append((rel, os.path.join(dirpath, fn)))

print("=== 发现的 MCA 文件 (%d) ===" % len(mca_files))
for rel, path in mca_files:
    print(" ", rel)


def handle_entity_root(root, source):
    eid = root.get("id")
    if not isinstance(eid, str):
        return
    entity_ids[eid] += 1
    if eid == MAID_ID:
        describe_maid(root, source)


def describe_maid(root, source):
    v = root.get("MaidRestrictCenter")
    maid_stats["MaidRestrictCenter"][tag_type_name(v) if v is not None else "MISSING"] += 1
    sched = root.get(SCHEDULE_KEY)
    maid_stats["MaidSchedulePos"][tag_type_name(sched) if sched is not None else "MISSING"] += 1
    if isinstance(sched, dict):
        for k in ("Work", "Idle", "Sleep"):
            sv = sched.get(k)
            maid_stats["MaidSchedulePos." + k][tag_type_name(sv) if sv is not None else "MISSING"] += 1
    if len(maid_samples) < 8:
        maid_samples.append((source, str(root.get("UUID")), tag_type_name(v), tag_type_name(sched)))


def handle_region_chunk(root, source):
    be = root.get("block_entities")
    if be is None:
        # 老格式（1.20.1 之前）TileEntities
        be = root.get("TileEntities")
    if be is None:
        return
    for t in be:
        if not isinstance(t, dict):
            continue
        eid = t.get("id")
        if eid == GOMOKU_ID:
            gomoku_stats["total"] += 1
            joy = t.get("TileEntityJoy")
            if joy is None:
                gomoku_stats["TileEntityJoy MISSING"] += 1
                gomoku_samples.append((source, "no TileEntityJoy", str(t.get("x")), str(t.get("y")), str(t.get("z"))))
                continue
            fd = joy.get("NeoForgeData")
            if fd is None:
                fd = joy.get("ForgeData")
            if fd is None:
                gomoku_stats["NeoForgeData MISSING"] += 1
                gomoku_samples.append((source, "no NeoForgeData/ForgeData", str(t.get("x")), str(t.get("y")), str(t.get("z"))))
                continue
            sit = fd.get("SitId")
            gomoku_stats["SitId:" + tag_type_name(sit) if sit is not None else "SitId MISSING"] += 1
            gomoku_samples.append((source, "SitId=" + tag_type_name(sit), str(t.get("x")), str(t.get("y")), str(t.get("z"))))


for rel, path in sorted(mca_files):
    parent = os.path.basename(os.path.dirname(path))
    kind = "entities" if parent == "entities" else "region"
    try:
        for entry in iter_mca_chunks(path):
            chunk_total += 1
            try:
                payload = decompress_payload(entry.compression, entry.payload)
                fileobj, root = parse_nbt(payload)
            except Exception as e:  # noqa: BLE001
                bad_chunks.append((rel, entry.cx, entry.cz, repr(e)))
                continue
            dv = root.get("DataVersion")
            dataversions["%s @ %s" % (dv, parent)] += 1

            if kind == "entities":
                # 1.17+ entities mca：根内 "Entities" 列表
                ent_list = root.get("Entities")
                if ent_list is not None:
                    for ent in ent_list:
                        if not isinstance(ent, dict):
                            continue
                        eid = ent.get("id")
                        if not isinstance(eid, str):
                            continue
                        entity_ids[eid] += 1
                        if eid == MAID_ID:
                            describe_maid(ent, "%s chunk(%d,%d)" % (rel, entry.cx, entry.cz))
                        elif eid == CHAIR_ID:
                            chair_count += 1
                            for d2, p in walk_containers(ent):
                                if d2 != "(root)":
                                    pid = p.get("id")
                                    chair_passengers[pid] += 1
                                    if pid == MAID_ID:
                                        describe_maid(p, "%s chunk(%d,%d) in-chair" % (rel, entry.cx, entry.cz))
                # 老格式残留：根内直接是实体列表
                elif root.get("id") is not None:
                    handle_entity_root(root, "%s chunk(%d,%d)" % (rel, entry.cx, entry.cz))
            else:
                handle_region_chunk(root, "%s chunk(%d,%d)" % (rel, entry.cx, entry.cz))
                # 检查 region chunk 根内是否有 entities 残留（1.20.1 无此键）
                if "entities" in root:
                    sub = root["entities"]
                    if isinstance(sub, dict):
                        for ent in sub:
                            if isinstance(ent, dict):
                                entity_ids["region-entities:" + str(ent.get("id"))] += 1
    except Exception as e:  # noqa: BLE001
        print("!! 文件级错误 %s: %r" % (rel, e))

print("\n=== chunk DataVersion 分布 ===")
for k, v in sorted(dataversions.items()):
    print("  %-40s %d" % (k, v))
print("chunk 总数: %d, 解析失败: %d" % (chunk_total, len(bad_chunks)))
for b in bad_chunks[:10]:
    print("   BAD:", b)

print("\n=== 实体统计 (top 30) ===")
for k, v in entity_ids.most_common(30):
    print("  %-50s %d" % (k, v))

print("\n=== maid NBT 现状 ===")
for k in sorted(maid_stats):
    print("  %-28s %s" % (k, dict(maid_stats[k])))
print("maid 样本:")
for s in maid_samples:
    print("  ", s)
print("\nchair 实体数: %d, 其 Passengers:" % chair_count, dict(chair_passengers))

print("\n=== gomoku 方块实体 ===")
for k, v in sorted(gomoku_stats.items()):
    print("  %-30s %d" % (k, v))
for s in gomoku_samples[:12]:
    print("  ", s)
