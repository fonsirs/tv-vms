from PIL import Image, ImageDraw, ImageFont
import make_icon as mi

ss = 3
W, H = 1000 * ss, 320 * ss
# máscara del logo (emblema + texto), con el destello como hueco
mask = Image.new("L", (W, H), 0)
cx, cy, r = 160 * ss, H // 2, 140 * ss
em = mi.emblem_mask((W, H), cx, cy, r)
mask.paste(255, (0, 0), em)
d = ImageDraw.Draw(mask)
hr = r * 0.11
hx, hy = cx - r * 0.17, cy - r * 0.19
d.ellipse((hx - hr, hy - hr, hx + hr, hy + hr), fill=0)
tm = mi.text_mask((W, H), "VMS", int(H * 0.62), (int(W * 0.36), H // 2), anchor="lm", spacing=int(H * 0.035))
mask.paste(255, (0, 0), tm)

bbox = mask.getbbox()
pad = 6 * ss
bbox = (max(bbox[0] - pad, 0), max(bbox[1] - pad, 0), min(bbox[2] + pad, W), min(bbox[3] + pad, H))
mask = mask.crop(bbox)
grad = mi.gradient(mask.size).convert("RGBA")
grad.putalpha(mask)
out_w = 600
out_h = round(out_w * grad.size[1] / grad.size[0])
grad = grad.resize((out_w, out_h), Image.LANCZOS)
grad.save("logo_bar.png")
print(grad.size)
