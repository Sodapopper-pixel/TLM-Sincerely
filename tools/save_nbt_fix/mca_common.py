# -*- coding: utf-8 -*-
"""MCA/NBT 公共工具：车万女仆旧存档（1.20.1 -> 1.21.1）修复用。

背景：
- 1.20.1 中 BlockPos 序列化为 CompoundTag {X: int, Y: int, Z: int}
- 1.21.1 中 NbtUtils.readBlockPos 仅接受长度为 3 的 IntArrayTag [X, Y, Z]
- touhou_little_maid 的 MaidRestrictCenter / MaidSchedulePos.{Work,Idle,Sleep}
  以及 TileEntityJoy 的 SitId 受此影响。
"""
import io
import os
import struct
import zlib
import gzip

import nbtlib
from nbtlib import File
from nbtlib.tag import Compound, IntArray, Int

# 目标实体 / 方块实体
MAID_ID = "touhou_little_maid:maid"
CHAIR_ID = "touhou_little_maid:chair"
GOMOKU_ID = "touhou_little_maid:gomoku"

MAID_POS_KEYS = ("MaidRestrictCenter",)  # 直接挂在 maid 根上的 BlockPos 键
SCHEDULE_KEY = "MaidSchedulePos"          # 其下 Work/Idle/Sleep 各是一个 BlockPos


class ChunkEntry:
    __slots__ = ("index", "cx", "cz", "timestamp", "compression", "payload", "raw")

    def __init__(self, index, cx, cz, timestamp, compression, payload, raw):
        self.index = index          # location 表下标 (0..1023)
        self.cx = cx                # 区块 X（region 内 0..31 偏移由调用方换算）
        self.cz = cz
        self.timestamp = timestamp  # 4 字节原始时间戳
        self.compression = compression
        self.payload = payload      # 解压后的 NBT 字节
        self.raw = raw              # 原始压缩字节（未修改 chunk 原样回写）


def iter_mca_chunks(path):
    """读取 MCA 文件，yield ChunkEntry。offset/count==0 或读到空跳过。"""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 8192:
        return
    locations = data[0:4096]
    timestamps = data[4096:8192]
    for i in range(1024):
        off = int.from_bytes(locations[i * 4:i * 4 + 3], "big")
        cnt = locations[i * 4 + 3]
        if off == 0 or cnt == 0:
            continue
        start = off * 4096
        if start + 5 > len(data):
            continue
        (length,) = struct.unpack(">I", data[start:start + 4])
        compression = data[start + 4]
        payload = data[start + 5:start + 4 + length]
        cx = (i % 32) + int(os.path.basename(path).split(".")[1]) * 32
        cz = (i // 32) + int(os.path.basename(path).split(".")[2]) * 32
        yield ChunkEntry(i, cx, cz, timestamps[i * 4:i * 4 + 4],
                         compression, payload, data[start:start + 4 + length])


def decompress_payload(compression, payload):
    if compression == 1:
        return gzip.decompress(payload)
    if compression == 2:
        return zlib.decompress(payload)
    if compression == 3:
        return payload
    raise ValueError("unknown compression type %d" % compression)


def compress_payload(data, compression):
    if compression == 1:
        return gzip.compress(data)
    return zlib.compress(data), 2


def parse_nbt(payload):
    """解析 chunk NBT -> (File, root_compound)。

    注意 nbtlib 2.0：File 本身即根 Compound（根内字段直接是它的 items），
    写回时 File.write 以空根名（现代 chunk 格式的根名）序列化。
    """
    fileobj = File.parse(io.BytesIO(payload))
    return fileobj, fileobj


def write_nbt(fileobj):
    buf = io.BytesIO()
    fileobj.write(buf)
    return buf.getvalue()


def rebuild_mca(path, entries):
    """用（可能修改过的）chunk 列表重建整个 MCA 文件。

    entries: list of (index, timestamp_bytes, compression_byte, final_payload_bytes)
    其中 final_payload_bytes 是不含长度前缀、不含压缩标记的已压缩数据。
    """
    out = bytearray(8192)
    body = bytearray()
    sector_no = 2  # 0/1 扇区是两块 header
    for index, ts, comp, compressed in entries:
        data = bytes([comp]) + compressed
        length = len(data)
        pad = (4096 - (length % 4096)) % 4096
        total_sectors = (length + pad) // 4096
        if total_sectors > 255:
            raise ValueError("chunk too large: %d sectors" % total_sectors)
        body += data + b"\x00" * pad
        out[index * 4:index * 4 + 3] = sector_no.to_bytes(3, "big")
        out[index * 4 + 3] = total_sectors
        out[4096 + index * 4:4096 + index * 4 + 4] = ts
        sector_no += total_sectors
    out += body
    with open(path, "wb") as f:
        f.write(bytes(out))
    return len(out)


def tag_type_name(tag):
    return type(tag).__name__


def blockpos_compound_to_intarray(tag):
    """{X,Y,Z} Compound -> IntArray [X,Y,Z]；非 Compound 或缺键返回 None。"""
    if not isinstance(tag, Compound):
        return None
    if not all(k in tag for k in ("X", "Y", "Z")):
        return None
    return IntArray([int(tag["X"]), int(tag["Y"]), int(tag["Z"])])


def entity_pos_to_intarray(root):
    """从实体根标签取 Pos (DoubleList) -> IntArray [int(x), int(y), int(z)]。"""
    pos = root.get("Pos")
    if pos is None or len(pos) < 3:
        return None
    return IntArray([int(float(pos[0])), int(float(pos[1])), int(float(pos[2]))])


def walk_containers(root):
    """遍历实体容器（找到嵌套在 Passengers 等处的 maid）。

    yield (holder_desc, entity_root)。
    """
    stack = [("(root)", root)]
    while stack:
        desc, node = stack.pop()
        yield desc, node
        if not isinstance(node, Compound):
            continue
        passengers = node.get("Passengers")
        if isinstance(passengers, nbtlib.tag.List):
            for i, p in enumerate(passengers):
                if isinstance(p, Compound):
                    stack.append(("%s.Passengers[%d]" % (desc, i), p))


def find_maid_entities(root):
    """在实体树中找 maid（含嵌套在 Passengers 里的），yield (path, root)。"""
    for desc, node in walk_containers(root):
        eid = node.get("id") if isinstance(node, Compound) else None
        if isinstance(eid, str) and eid == MAID_ID:
            yield desc, node


def find_typed_entities(root, eid_value):
    for desc, node in walk_containers(root):
        eid = node.get("id") if isinstance(node, Compound) else None
        if isinstance(eid, str) and eid == eid_value:
            yield desc, node
