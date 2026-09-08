"""Build smooth speed-sign artwork, glyph atlas, item icons and decorative resources.

Requires Pillow. Uses Alte DIN 1451 Mittelschrift Regular, bundled unchanged under OFL 1.1.
No signal assets outside speed_sign/ are regenerated.
"""
import json
import math
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/mtr_brsignal_addon"
OUT = ASSETS / "textures/block/speed_sign"
FONT = ASSETS / "font/alte_din_1451_mittelschrift_regular.ttf"
SIZE = 512
SCALE = 4
RED, YELLOW = "#e21e26", "#ffda22"


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def circle():
    return [(0.5 + 0.375 * math.cos(t * math.tau / 256), 0.625 + 0.375 * math.sin(t * math.tau / 256)) for t in range(256)]


def rounded_rectangle():
    points = []
    radius = 0.027
    for x, y, start in [(0.875-radius, 0.25-radius, 0), (0.125+radius, 0.25-radius, 90),
                        (0.125+radius, radius, 180), (0.875-radius, radius, 270)]:
        for step in range(17):
            angle = math.radians(start + step * 90 / 16)
            points.append((x + radius*math.cos(angle), y + radius*math.sin(angle)))
    return points


def pixel(point, resolution=SIZE*SCALE):
    return (point[0] * resolution, (1-point[1]) * resolution)


def plate(kind, outline, color):
    image = Image.new("RGBA", (SIZE*SCALE, SIZE*SCALE))
    draw = ImageDraw.Draw(image)
    vertices = [pixel(p) for p in outline]
    draw.polygon(vertices, fill="white")
    # The stroke is inside the physical outline, so the circle stays exactly 12/16 wide.
    mask = Image.new("L", image.size)
    ImageDraw.Draw(mask).polygon(vertices, fill=255)
    draw.line(vertices + vertices[:1], fill=color, width=round(0.05*SIZE*SCALE), joint="curve")
    image.putalpha(mask)
    image = image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    image.save(OUT / f"{kind}.png")
    write_json(OUT / f"{kind}.png.mcmeta", {"texture": {"blur": True, "clamp": True}})
    back = Image.new("RGBA", image.size, "#9c9fa0")
    back.putalpha(image.getchannel("A"))
    back.save(OUT / f"{kind}_back.png")
    write_json(OUT / f"{kind}_back.png.mcmeta", {"texture": {"blur": True, "clamp": True}})
    return image


def text_image(text, font, cap_height, max_width):
    bounds = font.getbbox(text)
    image = Image.new("RGBA", (bounds[2]-bounds[0]+4, bounds[3]-bounds[1]+4))
    ImageDraw.Draw(image).text((2-bounds[0], 2-bounds[1]), text, font=font, fill="black")
    ratio = min(cap_height / image.height, max_width / image.width)
    return image.resize((max(1, round(image.width*ratio)), max(1, round(image.height*ratio))), Image.Resampling.LANCZOS)


def with_text(image, lines, font, divider=False, divider_y=0.625, divider_width=0.5):
    image = image.copy()
    for text, center_y, cap_height, max_width in lines:
        glyphs = text_image(text, font, cap_height*SIZE, max_width*SIZE)
        image.alpha_composite(glyphs, (round((SIZE-glyphs.width)/2), round((1-center_y)*SIZE-glyphs.height/2)))
    if divider:
        draw = ImageDraw.Draw(image)
        draw.rectangle(((0.5-divider_width/2)*SIZE, (1-divider_y-0.006)*SIZE,
                        (0.5+divider_width/2)*SIZE, (1-divider_y+0.006)*SIZE), fill="black")
    return image


def arrow_image(image, direction):
    high = image.resize((SIZE*SCALE, SIZE*SCALE), Image.Resampling.NEAREST)
    draw = ImageDraw.Draw(high)
    # Two independent outward arrows, as in the reference, not a text "<->" glyph.
    for left in ([True, False] if direction == "both" else [direction == "left"]):
        # A single-direction plate is exactly one half of the original paired arrows.
        center = 0.325 if left else 0.675
        length = 0.28
        shape = [(-length/2, 0), (-length/2+0.092, 0.078), (-length/2+0.163, 0.078),
                 (-length/2+0.108, 0.027), (length/2, 0.027), (length/2, -0.027),
                 (-length/2+0.108, -0.027), (-length/2+0.163, -0.078), (-length/2+0.092, -0.078)]
        draw.polygon([pixel((center+(x if left else -x), 0.125+y)) for x, y in shape], fill="black")
    return high.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    FONT.parent.mkdir(parents=True, exist_ok=True)
    source = ROOT.parent / "MTR/fabric/src/main/resources/assets/mtr/textures/block/metal.png"
    if not source.exists():
        source = ASSETS / "textures/block/position_light/mtr_signal_pole.png"
    shutil.copyfile(source, OUT / "mtr_signal_pole.png")
    Image.new("RGBA", (4, 4), "white").save(OUT / "white.png")
    font = ImageFont.truetype(str(FONT), 120)
    characters = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    atlas = Image.new("RGBA", (1024, 1024))
    metrics = {}
    draw = ImageDraw.Draw(atlas)
    cap = font.getbbox("H")[3]-font.getbbox("H")[1]
    for index, char in enumerate(characters):
        x, y = (index % 8)*128 + 6, (index // 8)*128 + 6
        box = font.getbbox(char)
        draw.text((x-box[0], y-box[1]), char, font=font, fill="black")
        metrics[char] = {"u": x/1024, "v": y/1024, "w": box[2]-box[0], "h": box[3]-box[1], "advance": font.getlength(char)}
    atlas.save(OUT / "glyphs.png")
    write_json(OUT / "glyphs.png.mcmeta", {"texture": {"blur": True, "clamp": True}})

    contours = {"circle": circle(), "triangle": [(0.125, 1), (0.5, 0.25), (0.875, 1)],
                "arrow": rounded_rectangle()}
    layout = {"contours": contours, "glyphs": metrics, "cap_height": cap}
    write_json(ASSETS / "models/speed_sign_artwork.json", layout)
    plates = {}
    for shape in contours:
        for color, value in [("red", RED), ("yellow", YELLOW)]:
            plates[f"{shape}_{color}"] = plate(f"{shape}_{color}", contours[shape], value)
    samples = []
    for warning in (False, True):
        prefix = "advance_warning" if warning else "permanent_speed"
        color = "yellow" if warning else "red"
        shape = "triangle" if warning else "circle"
        for double in (False, True):
            name = prefix + "_sign" + ("_double" if double else "")
            if warning:
                lines = [("30", .850625, .165, .40), ("55", .655, .165, .29)] if double else [("50", .805, .26, .48)]
            else:
                lines = [("30", .785, .25, .48), ("55", .465, .25, .48)] if double else [("50", .625, .36, .55)]
            icon = with_text(plates[f"{shape}_{color}"], lines, font, double, .762625 if warning else .625, .32 if warning else .5)
            resources(name, icon)
            samples.append((name, icon))
        letter_lines = [("DMU", .850625, .165, .40), ("40", .655, .165, .29)] if warning else [("DMU", .774375, .18, .57), ("40", .48, .26, .48)]
        letter = with_text(plates[f"{shape}_{color}"], letter_lines, font)
        samples.append((prefix + "_DMU", letter))
        for direction in ("left", "both", "right"):
            name = prefix + "_arrow_" + direction
            icon = arrow_image(plates[f"arrow_{color}"], direction)
            icon.save(OUT / f"arrow_{color}_{direction}.png")
            write_json(OUT / f"arrow_{color}_{direction}.png.mcmeta", {"texture": {"blur": True, "clamp": True}})
            resources(name, icon)
            samples.append((name, icon))
    preview = Image.new("RGB", (6*300, 2*380), "#e5eaed")
    pd = ImageDraw.Draw(preview)
    label_font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 15)
    for index, (name, icon) in enumerate(samples):
        x, y = (index % 6)*300, (index//6)*380
        # Put arrows at the top in the comparison sheet only.
        if "_arrow_" in name:
            icon = icon.crop((0, 384, 512, 512))
            preview.paste(icon.resize((280, 70), Image.Resampling.LANCZOS), (x+10, y+20), icon.resize((280, 70), Image.Resampling.LANCZOS))
        else:
            resized = icon.resize((280, 280), Image.Resampling.LANCZOS)
            preview.paste(resized, (x+10, y+20), resized)
        pd.text((x+10, y+325), name.replace("permanent_speed", "PSR").replace("advance_warning", "AWI"), fill="#25282a", font=label_font)
    path = ROOT / "build/previews/speed_signs.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    preview.save(path)
    print(f"Generated 10 decorative blocks, smooth artwork and glyph atlas; preview: {path}")


def resources(name, icon):
    item_dir = ASSETS / "textures/item/speed_sign"
    item_dir.mkdir(parents=True, exist_ok=True)
    # Tight item bounds keep short arrow signs visible in the inventory.
    bounds = icon.getbbox()
    content = icon.crop(bounds)
    content.thumbnail((480, 480), Image.Resampling.LANCZOS)
    item = Image.new("RGBA", (512, 512))
    item.alpha_composite(content, ((512-content.width)//2, (512-content.height)//2))
    item.save(item_dir / f"{name}.png")
    write_json(ASSETS / f"models/item/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"mtr_brsignal_addon:item/speed_sign/{name}"}})
    write_json(ASSETS / f"models/block/{name}.json", {"parent": "minecraft:block/block", "textures": {"particle": "mtr_brsignal_addon:block/speed_sign/mtr_signal_pole"}, "elements": []})
    write_json(ASSETS / f"blockstates/{name}.json", {"variants": {"": {"model": f"mtr_brsignal_addon:block/{name}"}}})
    write_json(ROOT / f"src/main/resources/data/mtr_brsignal_addon/loot_tables/blocks/{name}.json",
               {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"mtr_brsignal_addon:{name}"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})


if __name__ == "__main__":
    main()
