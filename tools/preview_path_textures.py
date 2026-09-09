"""Preview authored path textures without rewriting the source images."""
from pathlib import Path
from PIL import Image
import math

ROOT = Path(__file__).resolve().parents[1]
input_dir = ROOT / "src/main/resources/assets/mtr_brsignal_addon/textures/block/path"
output = ROOT / "build/previews/path-textures.png"

scale = 16
cols = 6

# 单张图片放大后的尺寸（源图为 21×21）
img_size = 21 * scale

# 图片之间的间距
gap = 32

files = sorted(input_dir.glob("path_*.png"))

rows = math.ceil(len(files) / cols)

width = cols * img_size + (cols - 1) * gap
height = rows * img_size + (rows - 1) * gap

# 深灰背景
canvas = Image.new("RGB", (width, height), (40, 40, 40))

for i, filename in enumerate(files):
    with Image.open(filename) as source:
        img = source.convert("RGBA")

    # 保持像素风格放大
    img = img.resize(
        (img_size, img_size),
        Image.Resampling.NEAREST
    )

    x = (i % cols) * (img_size + gap)
    y = (i // cols) * (img_size + gap)

    canvas.paste(img, (x, y), img)

output.parent.mkdir(parents=True, exist_ok=True)
canvas.save(output)
print("完成:", output)
