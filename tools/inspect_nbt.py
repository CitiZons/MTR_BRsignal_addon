"""Tiny Minecraft NBT + region (.mca) inspector for debugging block-entity persistence."""
import gzip
import io
import struct
import sys
import zlib


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


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def read(self, n):
        out = self.data[self.pos:self.pos + n]
        if len(out) < n:
            raise EOFError("short read")
        self.pos += n
        return out

    def u8(self):
        return self.read(1)[0]

    def i8(self):
        return struct.unpack(">b", self.read(1))[0]

    def i16(self):
        return struct.unpack(">h", self.read(2))[0]

    def u16(self):
        return struct.unpack(">H", self.read(2))[0]

    def i32(self):
        return struct.unpack(">i", self.read(4))[0]

    def u32(self):
        return struct.unpack(">I", self.read(4))[0]

    def i64(self):
        return struct.unpack(">q", self.read(8))[0]

    def f32(self):
        return struct.unpack(">f", self.read(4))[0]

    def f64(self):
        return struct.unpack(">d", self.read(8))[0]

    def string(self):
        n = self.u16()
        return self.read(n).decode("utf-8", "replace")


def parse_payload(reader, tag_type, depth=0):
    if tag_type == TAG_BYTE:
        return reader.i8()
    if tag_type == TAG_SHORT:
        return reader.i16()
    if tag_type == TAG_INT:
        return reader.i32()
    if tag_type == TAG_LONG:
        return reader.i64()
    if tag_type == TAG_FLOAT:
        return reader.f32()
    if tag_type == TAG_DOUBLE:
        return reader.f64()
    if tag_type == TAG_BYTE_ARRAY:
        n = reader.i32()
        return list(reader.read(n))
    if tag_type == TAG_STRING:
        return reader.string()
    if tag_type == TAG_LIST:
        elem = reader.u8()
        n = reader.i32()
        return [parse_payload(reader, elem, depth + 1) for _ in range(n)]
    if tag_type == TAG_COMPOUND:
        out = {}
        while True:
            t = reader.u8()
            if t == TAG_END:
                break
            name = reader.string()
            out[name] = parse_payload(reader, t, depth + 1)
        return out
    if tag_type == TAG_INT_ARRAY:
        n = reader.i32()
        return [reader.i32() for _ in range(n)]
    if tag_type == TAG_LONG_ARRAY:
        n = reader.i32()
        return [reader.i64() for _ in range(n)]
    raise ValueError("unknown tag %d" % tag_type)


def parse_nbt(data):
    reader = Reader(data)
    t = reader.u8()
    if t == TAG_END:
        return {}
    name = reader.string()
    value = parse_payload(reader, t)
    return {name: value}


def load_nbt_file(path):
    with open(path, "rb") as f:
        raw = f.read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    return parse_nbt(raw)


def find_block_entities(root, wanted_ids, found):
    """Recursively find 'block_entities' lists and scan for wanted ids."""
    if isinstance(root, dict):
        if "id" in root and isinstance(root["id"], str) and root["id"] in wanted_ids:
            found.append(root)
        for v in root.values():
            find_block_entities(v, wanted_ids, found)
    elif isinstance(root, list):
        for v in root:
            find_block_entities(v, wanted_ids, found)


def inspect_region(path, wanted_ids, out):
    with open(path, "rb") as f:
        header = f.read(4096)
    for i in range(1024):
        packed = struct.unpack_from(">I", header, i * 4)[0]
        off = packed >> 8
        size = packed & 0xFF
        if off == 0 or size == 0:
            continue
        with open(path, "rb") as f:
            f.seek(off * 4096)
            chunk_header = f.read(5)
            if len(chunk_header) < 5:
                continue
            length, compression = struct.unpack(">IB", chunk_header)
            payload = f.read(length)
        try:
            if compression == 1:
                data = payload
            elif compression == 2:
                data = zlib.decompress(payload)
            else:
                continue
            chunk = parse_nbt(data)
        except Exception as exc:
            out.append((path, i, "PARSE_ERROR", str(exc)))
            continue
        section = chunk.get("", {})
        pos_tag = section.get("Position", None)
        pos = None
        if isinstance(pos_tag, list) and len(pos_tag) == 3:
            pos = tuple(pos_tag)
        found = []
        find_block_entities(section, wanted_ids, found)
        for be in found:
            out.append((path, i, pos, be))


def list_be_ids(path, out):
    with open(path, "rb") as f:
        header = f.read(4096)
    for i in range(1024):
        packed = struct.unpack_from(">I", header, i * 4)[0]
        off = packed >> 8
        size = packed & 0xFF
        if off == 0 or size == 0:
            continue
        with open(path, "rb") as f:
            f.seek(off * 4096)
            chunk_header = f.read(5)
            if len(chunk_header) < 5:
                continue
            length, compression = struct.unpack(">IB", chunk_header)
            payload = f.read(length)
        try:
            if compression == 1:
                data = payload
            elif compression == 2:
                data = zlib.decompress(payload)
            else:
                continue
            chunk = parse_nbt(data)
        except Exception as exc:
            out.append((path, i, "PARSE_ERROR", str(exc)))
            continue
        section = chunk.get("", {})
        bes = section.get("block_entities", [])
        if bes:
            ids = sorted(set(str(b.get("id")) for b in bes if isinstance(b, dict)))
            out.append((path, i, len(bes), ids))


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "saveddata"
    if mode == "saveddata":
        path = sys.argv[2]
        data = load_nbt_file(path)
        import json
        print(json.dumps(data, indent=1, default=str)[:8000])
        return
    if mode == "region":
        wanted = {"mtr_brsignal_addon:led_indicator", "mtr_brsignal_addon:indicator_1"}
        out = []
        for path in sys.argv[2:]:
            inspect_region(path, wanted, out)
        for path, idx, pos, be in out:
            if be == "PARSE_ERROR":
                print(f"{path} chunk {idx}: ERROR {pos}")
                continue
            x = be.get("x"); y = be.get("y"); z = be.get("z")
            print(f"{path} chunk {idx} chunkPos={pos} BE@{x},{y},{z} id={be.get('id')} "
                  f"bx={be.get('bx')} by={be.get('by')} bz={be.get('bz')} keys={sorted(be.keys())}")
        return
    if mode == "beids":
        out = []
        for path in sys.argv[2:]:
            list_be_ids(path, out)
        for path, idx, count, ids in out:
            print(f"{path} chunk {idx}: {count} BEs: {ids}")
        return


if __name__ == "__main__":
    main()
