from PIL import Image
import os
import math

input_dir = "."
output = "preview.png"

scale = 16
cols = 6

# 单张图片放大后的尺寸
img_size = 16 * scale

# 图片之间的间距
gap = 32

files = [
    f for f in os.listdir(input_dir)
    if f.lower().endswith(".png")
]

files.sort()

rows = math.ceil(len(files) / cols)

width = cols * img_size + (cols - 1) * gap
height = rows * img_size + (rows - 1) * gap

# 深灰背景
canvas = Image.new("RGB", (width, height), (40, 40, 40))

for i, filename in enumerate(files):
    img = Image.open(os.path.join(input_dir, filename)).convert("RGBA")

    # 保持像素风格放大
    img = img.resize(
        (img_size, img_size),
        Image.Resampling.NEAREST
    )

    x = (i % cols) * (img_size + gap)
    y = (i // cols) * (img_size + gap)

    canvas.paste(img, (x, y), img)

canvas.save(output)
print("完成:", output)