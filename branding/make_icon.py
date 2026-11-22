from PIL import Image, ImageDraw, ImageFont, ImageChops, ImageFilter

TOP = (229, 198, 120)     # dorado claro
MID = (201, 160, 75)      # #C9A04B
BOT = (160, 118, 44)      # dorado oscuro
FONT = "/usr/share/fonts/truetype/google-fonts/ofl/montserrat/Montserrat[wght].ttf"

def gradient(size, vertical=True):
    w, h = size
    g = Image.new("RGB", size)
    px = g.load()
    n = h if vertical else w
    for i in range(n):
        t = i / max(1, n - 1)
        if t < 0.5:
            u = t / 0.5
            c = tuple(int(TOP[k] + (MID[k] - TOP[k]) * u) for k in range(3))
        else:
            u = (t - 0.5) / 0.5
            c = tuple(int(MID[k] + (BOT[k] - MID[k]) * u) for k in range(3))
        if vertical:
            for x in range(w): px[x, i] = c
        else:
            for y in range(h): px[i, y] = c
    return g

def gold_paste(base, mask, box=None):
    g = gradient(base.size)
    base.paste(g, (0, 0), mask)

def emblem_mask(size, cx, cy, r):
    """Objetivo de cámara: anillo exterior, anillo fino, pupila y destello."""
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)                  # disco exterior
    d.ellipse((cx - r * 0.80, cy - r * 0.80, cx + r * 0.80, cy + r * 0.80), fill=0)   # hueco
    d.ellipse((cx - r * 0.66, cy - r * 0.66, cx + r * 0.66, cy + r * 0.66), fill=255) # anillo fino
    d.ellipse((cx - r * 0.60, cy - r * 0.60, cx + r * 0.60, cy + r * 0.60), fill=0)
    d.ellipse((cx - r * 0.44, cy - r * 0.44, cx + r * 0.44, cy + r * 0.44), fill=255) # pupila
    return m

def highlight(img, cx, cy, r):
    d = ImageDraw.Draw(img)
    hr = r * 0.11
    hx, hy = cx - r * 0.17, cy - r * 0.19
    d.ellipse((hx - hr, hy - hr, hx + hr, hy + hr), fill=(255, 255, 255))

def text_mask(size, text, font_px, xy, anchor="mm", weight=800, spacing=0):
    f = ImageFont.truetype(FONT, font_px)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    # tracking manual
    widths = [d.textlength(ch, font=f) for ch in text]
    total = sum(widths) + spacing * (len(text) - 1)
    if anchor == "mm":
        x = xy[0] - total / 2
    else:
        x = xy[0]
    for ch, w in zip(text, widths):
        d.text((x, xy[1]), ch, font=f, fill=255, anchor="lm")
        x += w + spacing
    return m

def square(S=1024):
    ss = 2
    N = S * ss
    img = Image.new("RGB", (N, N), (255, 255, 255))
    cx, cy, r = N // 2, int(N * 0.37), int(N * 0.235)
    gold_paste(img, emblem_mask((N, N), cx, cy, r))
    highlight(img, cx, cy, r)
    tm = text_mask((N, N), "VMS", int(N * 0.30), (N // 2, int(N * 0.80)), spacing=int(N * 0.012))
    gold_paste(img, tm)
    return img.resize((S, S), Image.LANCZOS)

def banner(W=640, H=360):
    ss = 3
    w, h = W * ss, H * ss
    img = Image.new("RGB", (w, h), (255, 255, 255))
    cx, cy, r = int(w * 0.22), h // 2, int(h * 0.29)
    gold_paste(img, emblem_mask((w, h), cx, cy, r))
    highlight(img, cx, cy, r)
    tm = text_mask((w, h), "VMS", int(h * 0.36), (int(w * 0.43), h // 2), anchor="lm", spacing=int(h * 0.02))
    gold_paste(img, tm)
    return img.resize((W, H), Image.LANCZOS)

if __name__ == "__main__":
    sq = square(1024); sq.save("icon_1024.png")
    bn = banner(); bn.save("banner_640x360.png")
    print("ok")
