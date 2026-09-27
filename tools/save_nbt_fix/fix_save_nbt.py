# -*- coding: utf-8 -*-
"""车万女仆旧存档（1.20.1 -> 1.21.1）NBT 修复脚本。

修复内容（问题 1 方案 B）：
1. MCA（entities/region，含维度目录若有 .mca）：
   - touhou_little_maid:maid（含嵌套在 Passengers 中的）：
     * MaidRestrictCenter: Compound{X,Y,Z} -> IntArray [X,Y,Z]
     * MaidSchedulePos.Work/Idle/Sleep: Compound{X,Y,Z} -> IntArray [X,Y,Z]；
       缺失或无效时用实体 Pos 的 floor(x),floor(y),floor(z) 填补
   - touhou_little_maid:gomoku 方块实体：TileEntityJoy 的
     NeoForgeData/ForgeData 缺少 SitId 时补 NIL_UUID IntArray [0,0,0,0]
   - 仅重建包含修改的 MCA 文件，其余保持原字节
2. data/maid_backups/**\/*.dat（女仆幸存数据备份）：
   - MaidSchedulePos.Work/Idle/Sleep 同上转换

背景：1.20.1 的 BlockPos 序列化为 Compound{X,Y,Z}，1.21.1 的
NbtUtils.readBlockPos 仅接受 IntArray [X,Y,Z]。
"""
import io
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mca_common import (  # noqa: E402
    iter_mca_chunks, decompress_payload, parse_nbt, write_nbt, rebuild_mca,
    walk_containers, tag_type_name, MAID_ID, GOMOKU_ID, SCHEDULE_KEY,
)
from nbtlib.tag import Compound, IntArray  # noqa: E402

WORLD = os.path.abspath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "saves", "新的世界"))

report = {"mca_files_seen": 0, "mca_files_rewritten": 0, "chunks_seen": 0,
          "chunks_modified": 0, "maids_fixed": 0, "gomoku_fixed": 0,
          "backup_files": 0, "backup_fixed": 0}
logs = []


def blockpos_compound(tag):
    """返回 (x, y, z) 若 tag 是合法 BlockPos Compound，否则 None。"""
    if isinstance(tag, Compound) and all(k in tag for k in ("X", "Y", "Z")):
        try:
            return int(tag["X"]), int(tag["Y"]), int(tag["Z"])
        except Exception:  # noqa: BLE001
            return None
    return None


def entity_pos_floor(root):
    pos = root.get("Pos")
    if pos is None or len(pos) < 3:
        return None
    import math
    return [int(math.floor(float(pos[i]))) for i in range(3)]


def fix_blockpos_key(parent, key, fallback, ctx, counter):
    """parent[key] 若为 BlockPos Compound 则转 IntArray；缺失/无效用 fallback 填补。

    返回是否修改。fallback 为 None 且需要填补时不动（记录警告）。
    """
    tag = parent.get(key) if isinstance(parent, Compound) else None
    if tag is None:
        if fallback is not None:
            parent[key] = IntArray(fallback)
            logs.append("  [FIX] %s: %s MISSING -> IntArray %s" % (ctx, key, fallback))
            counter["fixed"] += 1
            return True
        logs.append("  [WARN] %s: %s missing and no Pos fallback" % (ctx, key))
        return False
    xyz = blockpos_compound(tag)
    if xyz is not None:
        parent[key] = IntArray(list(xyz))
        logs.append("  [FIX] %s: %s Compound%s -> IntArray %s" % (ctx, key, xyz, xyz))
        counter["fixed"] += 1
        return True
    if isinstance(tag, IntArray) and len(tag) == 3:
        return False  # 已是正确格式
    # 类型不对且非合法 compound：视为无效，用 fallback
    if fallback is not None:
        parent[key] = IntArray(fallback)
        logs.append("  [FIX] %s: %s invalid(%s) -> IntArray %s"
                    % (ctx, key, tag_type_name(tag), fallback))
        counter["fixed"] += 1
        return True
    logs.append("  [WARN] %s: %s invalid(%s), no fallback" % (ctx, key, tag_type_name(tag)))
    return False


def fix_maid_entity(root, ctx, counter):
    """修复单个 maid 实体根标签。"""
    changed = False
    pos = entity_pos_floor(root)
    center = root.get("MaidRestrictCenter")
    if center is not None and isinstance(center, Compound) and blockpos_compound(center) is not None:
        xyz = blockpos_compound(center)
        root["MaidRestrictCenter"] = IntArray(list(xyz))
        logs.append("  [FIX] %s: MaidRestrictCenter Compound%s -> IntArray %s" % (ctx, xyz, xyz))
        counter["fixed"] += 1
        changed = True
    elif center is None:
        # 任务规范只要求转换存在的 Compound；缺失时用 Pos 填补（与 SchedulePos 一致的兜底）
        if pos is not None:
            root["MaidRestrictCenter"] = IntArray(pos)
            logs.append("  [FIX] %s: MaidRestrictCenter MISSING -> IntArray(from Pos) %s" % (ctx, pos))
            counter["fixed"] += 1
            changed = True
    sched = root.get(SCHEDULE_KEY)
    if not isinstance(sched, Compound):
        if sched is None:
            if pos is not None:
                nc = Compound({"Work": IntArray(pos), "Idle": IntArray(pos), "Sleep": IntArray(pos)})
                nc["Dimension"] = root.get("Dimension", "minecraft:overworld") \
                    if not isinstance(root.get("Dimension"), str) else root["Dimension"]
                root[SCHEDULE_KEY] = nc
                logs.append("  [FIX] %s: MaidSchedulePos MISSING -> filled from Pos %s" % (ctx, pos))
                counter["fixed"] += 1
                changed = True
            else:
                logs.append("  [WARN] %s: MaidSchedulePos missing and no Pos" % ctx)
        else:
            logs.append("  [WARN] %s: MaidSchedulePos unexpected type %s" % (ctx, tag_type_name(sched)))
        return changed
    s_changed = False
    for key in ("Work", "Idle", "Sleep"):
        if fix_blockpos_key(sched, key, pos, ctx, counter):
            s_changed = True
    return changed or s_changed


def fix_gomoku_be(be, ctx, counter):
    """修复五子棋方块实体的 SitId。"""
    joy = be.get("TileEntityJoy")
    if not isinstance(joy, Compound):
        logs.append("  [WARN] %s: gomoku without TileEntityJoy" % ctx)
        return False
    data = None
    for key in ("NeoForgeData", "ForgeData"):
        if isinstance(joy.get(key), Compound):
            data = joy[key]
            data_key = key
            break
    if data is None:
        data = Compound()
        joy["NeoForgeData"] = data
        data_key = "NeoForgeData"
    sit = data.get("SitId")
    if sit is None:
        data["SitId"] = IntArray([0, 0, 0, 0])
        logs.append("  [FIX] %s: TileEntityJoy.%s.SitId MISSING -> IntArray [0,0,0,0]" % (ctx, data_key))
        counter["fixed"] += 1
        return True
    if isinstance(sit, IntArray) and len(sit) == 4:
        return False
    logs.append("  [FIX] %s: SitId invalid(%s) -> IntArray [0,0,0,0]" % (ctx, tag_type_name(sit)))
    data["SitId"] = IntArray([0, 0, 0, 0])
    counter["fixed"] += 1
    return True


def fix_mca_tree():
    print("=== 阶段 A：扫描并修复 MCA 文件 ===")
    mca_files = []
    for dirpath, _dirs, files in os.walk(WORLD):
        if os.sep + "maid_backups" + os.sep in dirpath + os.sep:
            continue
        for fn in files:
            if fn.endswith(".mca") or fn.endswith(".mcr"):
                mca_files.append((os.path.relpath(os.path.join(dirpath, fn), WORLD),
                                  os.path.join(dirpath, fn)))
    for rel, path in sorted(mca_files):
        kind = "entities" if os.path.basename(os.path.dirname(path)) == "entities" else "region"
        report["mca_files_seen"] += 1
        modified_entries = []  # (index, ts, comp, compressed)
        try:
            for entry in iter_mca_chunks(path):
                report["chunks_seen"] += 1
                payload = decompress_payload(entry.compression, entry.payload)
                fileobj, root = parse_nbt(payload)
                chunk_changed = False
                counter = {"fixed": 0}

                if kind == "entities":
                    ent_list = root.get("Entities")
                    for ent in (ent_list if ent_list is not None else []):
                        if not isinstance(ent, Compound):
                            continue
                        for desc, node in walk_containers(ent):
                            eid = node.get("id")
                            if isinstance(eid, str) and eid == MAID_ID:
                                ctx = "%s chunk(%d,%d) %s" % (rel, entry.cx, entry.cz, desc)
                                if fix_maid_entity(node, ctx, counter):
                                    chunk_changed = True
                else:
                    for be in (root.get("block_entities") or []):
                        if isinstance(be, Compound) and be.get("id") == GOMOKU_ID:
                            ctx = "%s chunk(%d,%d) BE@%s,%s,%s" % (
                                rel, entry.cx, entry.cz, be.get("x"), be.get("y"), be.get("z"))
                            if fix_gomoku_be(be, ctx, counter):
                                chunk_changed = True

                if chunk_changed:
                    report["chunks_modified"] += 1
                    report["maids_fixed"] += counter["fixed"]
                    new_payload = write_nbt(fileobj)
                    modified_entries.append((entry.index, entry.timestamp, 2, new_payload, True))
                else:
                    modified_entries.append((entry.index, entry.timestamp,
                                             entry.compression, entry.payload, False))
        except Exception as e:  # noqa: BLE001
            print("!! 文件级错误 %s: %r（跳过，不重写）" % (rel, e))
            continue
        if any(e[4] for e in modified_entries):
            old_size = os.path.getsize(path)
            entries = [(e[0], e[1], e[2], e[3]) for e in modified_entries]
            new_size = rebuild_mca(path, entries)
            report["mca_files_rewritten"] += 1
            print("  [REWRITE] %s  %d -> %d bytes" % (rel, old_size, new_size))


def fix_maid_backups():
    print("\n=== 阶段 B：修复 maid_backups 女仆数据备份 ===")
    base = os.path.join(WORLD, "data", "maid_backups")
    if not os.path.isdir(base):
        print("  （无 maid_backups 目录）")
        return
    for dirpath, _dirs, files in os.walk(base):
        for fn in sorted(files):
            if not fn.endswith(".dat") or fn == "index.dat":
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, WORLD)
            try:
                raw = gzip.decompress(open(path, "rb").read())
                fileobj, root = parse_nbt(raw)
            except Exception as e:  # noqa: BLE001
                print("  [SKIP] %s: %r" % (rel, e))
                continue
            counter = {"fixed": 0}
            pos = entity_pos_floor(root)
            ctx = rel
            changed = False
            sched = root.get(SCHEDULE_KEY)
            if isinstance(sched, Compound):
                for key in ("Work", "Idle", "Sleep"):
                    if fix_blockpos_key(sched, key, pos, ctx, counter):
                        changed = True
            else:
                logs.append("  [WARN] %s: MaidSchedulePos %s"
                            % (ctx, tag_type_name(sched) if sched is not None else "MISSING"))
            report["backup_files"] += 1
            if changed:
                out = io.BytesIO()
                fileobj.write(out)
                with open(path, "wb") as f:
                    f.write(gzip.compress(out.getvalue()))
                report["backup_fixed"] += 1
                print("  [FIX] %s（%d 处）" % (rel, counter["fixed"]))


if __name__ == "__main__":
    fix_mca_tree()
    fix_maid_backups()
    print("\n=== 修复日志（明细）===")
    for line in logs:
        print(line)
    print("\n=== 汇总 ===")
    for k, v in report.items():
        print("  %-24s %s" % (k, v))
