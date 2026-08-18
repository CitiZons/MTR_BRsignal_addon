"""Convert a Blockbench java_block bbmodel into a Forge OBJ block model.

Rules derived from Blockbench's own OBJ export + Forge's OBJ loader:
- bbmodel pixel coordinates are divided by 16 to get block units;
- the model's horizontal center lands on the block's west edge (x = px / 16),
  which matches the user's requested -0.5 block west shift;
- every cube face is emitted (faces without a texture get the black material),
  so the model is fully closed;
- optional Y rotation around the block center (0.5, 0.5, 0.5), clockwise from above,
  matching vanilla blockstate "y" rotation so variants compose with facing rotations.
"""
import json
import math
import sys


TEX_MTL = {0: "indicator_1", 1: "grey", 2: "indicator_1_route_1", 3: "black", 4: "indicator_1_back"}


def face_geometry(x1, y1, z1, x2, y2, z2, name):
    """Return the 4 corner coordinates (pixel space) and normal for a cube face.
    UV convention: corner 0 = top-left of the face texture (u1,v1), corner 3 = bottom-left (u1,v2)."""
    faces = {
        "north": ([(x1, y2, z1), (x2, y2, z1), (x2, y1, z1), (x1, y1, z1)], (0, 0, -1)),
        "south": ([(x1, y2, z2), (x2, y2, z2), (x2, y1, z2), (x1, y1, z2)], (0, 0, 1)),
        "east": ([(x2, y2, z2), (x2, y2, z1), (x2, y1, z1), (x2, y1, z2)], (1, 0, 0)),
        "west": ([(x1, y2, z1), (x1, y2, z2), (x1, y1, z2), (x1, y1, z1)], (-1, 0, 0)),
        "up": ([(x1, y2, z2), (x2, y2, z2), (x2, y2, z1), (x1, y2, z1)], (0, 1, 0)),
        "down": ([(x1, y1, z1), (x2, y1, z1), (x2, y1, z2), (x1, y1, z2)], (0, -1, 0)),
    }
    return faces[name]


def rotate_y(x, z, angle_deg):
    """Rotate around Y by angle_deg (clockwise from above, matching vanilla blockstate y rotation)."""
    rad = math.radians(angle_deg)
    cos = math.cos(rad)
    sin = math.sin(rad)
    dx = x - 0.5
    dz = z - 0.5
    return 0.5 + dx * cos - dz * sin, 0.5 + dx * sin + dz * cos


def main():
    bb_path = sys.argv[1]
    out_path = sys.argv[2]
    angle = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0

    with open(bb_path, "r", encoding="utf-8") as f:
        bb = json.load(f)

    lines = ["mtllib indicator_1.mtl"]
    vertices = []
    face_count = 0
    # Blockbench 的 UV 空间按纹理条目 uv_width/uv_height（这里都是 32x32）归一化，
    # 而不是按 PNG 实际尺寸（64x64 / 16x16），否则会错误地只裁出左上四分之一。
    uv_size = {}
    for tex in bb.get("textures", []):
        try:
            uv_size[tex["id"]] = (tex.get("uv_width", 32), tex.get("uv_height", 32))
        except (KeyError, TypeError):
            pass

    for element in bb["elements"]:
        if element.get("export") is False:
            continue
        route_texture_id = next((tid for tid, mtl in TEX_MTL.items() if mtl == "indicator_1_route_1"), None)
        if route_texture_id is not None and any(
                isinstance(face.get("texture"), int) and face.get("texture") == route_texture_id
                for face in element.get("faces", {}).values()):
            continue
        x1, y1, z1 = element["from"]
        x2, y2, z2 = element["to"]
        faces = element.get("faces", {})
        has_board_texture = any(
                isinstance(face.get("texture"), int) and face.get("texture") in (0, 4)
                for face in faces.values())
        for face_name in ("north", "south", "east", "west", "up", "down"):
            if face_name not in faces:
                continue
            face = faces[face_name]
            # 面板（带 indicator 正/背面贴图的元素）的侧面不渲染，保证 2x2 背板是平板
            texture_id = face.get("texture")
            if has_board_texture and (texture_id is None or texture_id < 0):
                # 面板侧边：保持透明（不渲染），恢复上一版行为
                continue
            corners, normal = face_geometry(x1, y1, z1, x2, y2, z2, face_name)
            texture_id = face.get("texture")
            if texture_id is None or texture_id < 0 or texture_id not in TEX_MTL:
                material = "black"
                tex_w = tex_h = 32
                uv = (0, 0, 32, 32)
            else:
                material = TEX_MTL[texture_id]
                tex_w, tex_h = uv_size.get(texture_id, (32, 32))
                uv = face.get("uv") or (0, 0, tex_w, tex_h)
            u1, v1, u2, v2 = uv
            uv_corners = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
            # 北面（背面）按 Blockbench 导出的惯例水平镜像：u1 对应 x2（东）
            if face_name == "north":
                uv_corners = [(u2, v1), (u1, v1), (u1, v2), (u2, v2)]
            lines.append("usemtl %s" % material)
            index_base = len(vertices) + 1
            for (px, py, pz), (u, v) in zip(corners, uv_corners):
                bx = px / 16.0
                by = py / 16.0
                bz = pz / 16.0
                if angle:
                    bx, bz = rotate_y(bx, bz, angle)
                vertices.append((bx, by, bz))
                lines.append("v %.6f %.6f %.6f" % (bx, by, bz))
                lines.append("vt %.6f %.6f" % (u / tex_w, v / tex_h))
                lines.append("vn %d %d %d" % normal)
            a, b, c, d = (index_base + i for i in range(4))
            lines.append("f %d/%d/%d %d/%d/%d %d/%d/%d" % (a, a, a, b, b, b, c, c, c))
            lines.append("f %d/%d/%d %d/%d/%d %d/%d/%d" % (a, a, a, c, c, c, d, d, d))
            face_count += 1

    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote %s: %d vertices, %d faces" % (out_path, len(vertices), face_count))


if __name__ == "__main__":
    main()
