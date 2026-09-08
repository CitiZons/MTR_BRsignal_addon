"""Restore the eight indicator preview sheets without regenerating model assets.

Run: python tools/preview_indicators.py
Outputs: build/previews/*.png
"""
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from generate_six_route_indicators import BILATERAL, COMBINATIONS
from generate_triple_indicators import rotate

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/mtr_brsignal_addon"
OUT = ROOT / "build/previews"
FONT_PATH = Path("C:/Windows/Fonts/segoeui.ttf")


def font(size):
    return ImageFont.truetype(str(FONT_PATH), size) if FONT_PATH.exists() else ImageFont.load_default(size=size)


def load(name):
    return json.loads((ASSETS / "models/block" / f"{name}.json").read_text(encoding="utf-8-sig"))


def cuboid_faces(box):
    x, y, z, X, Y, Z = box
    return (
        [(x,y,z),(x,Y,z),(X,Y,z),(X,y,z)],
        [(x,y,Z),(X,y,Z),(X,Y,Z),(x,Y,Z)],
        [(x,y,z),(x,y,Z),(x,Y,Z),(x,Y,z)],
        [(X,y,Z),(X,y,z),(X,Y,z),(X,Y,Z)],
        [(x,Y,Z),(X,Y,Z),(X,Y,z),(x,Y,z)],
        [(x,y,z),(X,y,z),(X,y,Z),(x,y,Z)],
    )


def render(name, yaw=0, elev=0, block_y=0, data=None, height=290, center_y=8):
    model = data if data is not None else load(name)
    image = Image.new("RGB", (340, height), (74,86,99))
    draw = ImageDraw.Draw(image)
    c, s = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    ce, se = math.cos(math.radians(elev)), math.sin(math.radians(elev))
    cr, sr = math.cos(math.radians(-block_y)), math.sin(math.radians(-block_y))
    polygons = []

    def camera(point):
        x, y, z = point[0]-8, point[1]-center_y, point[2]-8
        x, z = x*cr+z*sr, -x*sr+z*cr
        h = x*s+z*c
        return x*c-z*s, y*ce-h*se, y*se+h*ce

    colors = {"back":(34,34,34), "hood":(17,17,17), "strip":(17,17,17),
              "base":(105,105,105), "light":(255,255,255)}
    for element in model["elements"]:
        angle = element.get("rotation", {}).get("angle", 0)
        material = next(iter(element["faces"].values()))["texture"].lstrip("#")
        if material not in colors:
            name = element.get("name", "")
            material = ("light" if "_route_" in name else "back" if "_back_" in name
                        else "strip" if "_strip_" in name else "hood" if name else "base")
        for face in cuboid_faces(element["from"] + element["to"]):
            points = [camera(rotate(point, angle)) for point in face]
            u = [points[1][i]-points[0][i] for i in range(3)]
            v = [points[2][i]-points[0][i] for i in range(3)]
            normal = [u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0]]
            if normal[2] <= 1e-8:
                continue
            length = math.sqrt(sum(value*value for value in normal))
            brightness = 1 if material == "light" else .65+.35*normal[2]/length+.3*max(0,normal[1]/length)
            color = tuple(min(255,int(ch*brightness)) for ch in colors[material])
            polygons.append((sum(p[2] for p in points)/len(points),
                             [(170+p[0]*15,height/2-p[1]*15) for p in points],color))
    for _, points, color in sorted(polygons, key=lambda polygon: polygon[0]):
        draw.polygon(points, fill=color)
    return image


def all_lit(prefix, routes):
    model = load(prefix + "_null")
    lamps = {}
    for route in routes:
        for element in load(f"{prefix}_{route}")["elements"]:
            if "_route_" in element.get("name", ""):
                lamps[tuple(element["from"]+element["to"])] = element
    model["elements"].extend(lamps.values())
    return model


def sheet(size, title=None):
    image = Image.new("RGB", size, (28,36,46))
    draw = ImageDraw.Draw(image)
    if title:
        draw.text((18,12), title, font=font(19), fill="white")
    return image, draw


def save(image, name):
    path = OUT / (name + ".png")
    image.save(path)
    print(path)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    textures = ASSETS / "textures/block/indicator"
    names = [base+suffix for base in ("indicator_1-2", "indicator_1-4", "indicator_4-5")
             for suffix in ("", "_back", "_strip", "_route_1", "_route_2", "_route_4", "_route_5")
             if (textures / (base+suffix+".png")).exists()]
    image = Image.new("RGB", (1150,765), (75,86,98))
    draw = ImageDraw.Draw(image)
    for i, name in enumerate(names):
        x, y = i%5*230, i//5*255
        with Image.open(textures / (name+".png")) as source:
            tile = source.convert("RGBA").resize((192,192), Image.Resampling.NEAREST)
        image.paste(tile, (x+16,y+30), tile)
        draw.text((x+8,y+8), name, font=font(14), fill="white")
    save(image, "existing-textures")

    for filename, names, size in (
        ("latest-authored-backs", ("1-2-3","1-2-3-4","1-2-3-4-5","1-2-3-4-5-6"), (1024,300)),
        ("revised-authored-backplates", ("1-2-3","1-2-3-4","1-2-3-4-5"), (768,290)),
    ):
        image = Image.new("RGB", size, "#506070")
        draw = ImageDraw.Draw(image)
        for i, name in enumerate(names):
            with Image.open(textures / f"indicator_{name}_back.png") as source:
                tile = source.convert("RGBA").resize((256,256), Image.Resampling.NEAREST)
            image.paste(tile, (i*256,30), tile)
            draw.text((i*256+8,8), name, font=font(14), fill="white")
        save(image, filename)

    image, draw = sheet((680,640))
    for i, suffix in enumerate(("1-2-3","4-5-6","1-2-3-4-5","1-2-4-5-6")):
        prefix = "indicator_" + suffix
        x, y = i%2*340, i//2*320
        image.paste(render(prefix, data=all_lit(prefix, COMBINATIONS[prefix])), (x,y+30))
        draw.text((x+16,y+8), prefix, font=font(14), fill="white")
    save(image, "latest-mirrored-backplates")

    # Simultaneous lamps identify geometry only; game authorization lights one route.
    image, draw = sheet((1020,1040), "9 bilateral combinations | all route positions (geometry preview)")
    for i, routes in enumerate(BILATERAL):
        prefix = "indicator_" + "-".join(map(str,routes))
        x, y = i%3*340, 50+i//3*330
        image.paste(render(prefix, data=all_lit(prefix,routes)), (x,y+28))
        draw.text((x+18,y+4), prefix, font=font(14), fill="white")
    save(image, "six-routes-combinations")

    image, draw = sheet((1020,720), "1-2-3-4-5-6 | individual authorized routes")
    for i in range(6):
        x, y = i%3*340, 50+i//3*330
        image.paste(render(f"indicator_1-2-3-4-5-6_{i+1}"), (x,y+28))
        draw.text((x+18,y+4), f"route={i+1}", font=font(19), fill="white")
    save(image, "six-routes-authorized")

    image, draw = sheet((1020,720), "Raised centre / grounded support / rotated shells")
    views = [("indicator_1-2-3",0,0,0), ("indicator_4-5-6",0,0,0),
             ("indicator_1-2-3",25,18,0), ("indicator_4-5-6",-25,18,0),
             ("indicator_1-2-3-4-5-6",155,18,0), ("indicator_1-2-3-4-5-6",0,10,45)]
    for i, (prefix,yaw,elev,block_y) in enumerate(views):
        x, y = i%3*340, 50+i//3*330
        image.paste(render(prefix,yaw,elev,block_y,all_lit(prefix,COMBINATIONS[prefix])), (x,y+28))
        draw.text((x+18,y+4), prefix, font=font(14), fill="white")
    save(image, "six-routes-support")

    image, draw = sheet((1360,580))
    for row, (prefix,routes) in enumerate((("indicator_1-2-4",(1,2,4)), ("indicator_1-4-5",(1,4,5)))):
        for col, route in enumerate(("null",)+routes):
            x, y = col*340, row*290
            image.paste(render(f"{prefix}_{route}", height=235, center_y=5.5), (x,y+40))
            draw.text((x+12,y+12), f"{prefix} | {'off' if route == 'null' else f'route={route}'}", font=font(14), fill="white")
    save(image, "triple-indicators-front")


if __name__ == "__main__":
    main()
