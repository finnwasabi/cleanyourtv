# The liquid-glass wallpaper: translucent dark silk ribbons that sweep from the lower left to the upper right, each
# lit along its upper edge with a sheen fold inside, so glass over them picks up colour; the top left stays dark for
# the clock. Deterministic. Usage: python3 wallpaper.py wallpaper.jpg [preview.png] (needs numpy and Pillow).
import sys, numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080
x = np.linspace(0, 1, W, dtype=np.float32)[None, :]
y = np.linspace(0, 1, H, dtype=np.float32)[:, None]
X = np.broadcast_to(x, (H, W)); Y = np.broadcast_to(y, (H, W))
def hexc(h): return np.array([int(h[i:i+2], 16) / 255 for i in (1, 3, 5)], np.float32)
INK, MIDNIGHT, INDIGO, IRIS, GLACIER, ROSE = map(hexc, ["#02040B", "#0A1233", "#2733A0", "#6A4FE0", "#9CCBFF", "#E58FD0"])
def blur(a, r):
    if a.ndim == 2:
        im = Image.fromarray(np.clip(a * 255, 0, 255).astype(np.uint8))
        return np.asarray(im.filter(ImageFilter.GaussianBlur(r)), np.float32) / 255
    return np.stack([blur(a[..., i], r) for i in range(3)], -1)

img = np.broadcast_to(INK, (H, W, 3)).copy()
# A deep glow behind everything, low on the right.
img += (MIDNIGHT * 0.9)[None, None] * np.exp(-(((X - 0.78) / 0.55) ** 2 + ((Y - 0.85) / 0.55) ** 2))[..., None]

def ribbon(top, thick, col, hi, alpha, sheen_at, sheen):
    t0 = top(x); t1 = t0 + thick(x)
    if isinstance(col, tuple):   # colour along the ribbon: left end to right end
        k = np.clip(X * 1.15 - 0.1, 0, 1)[..., None]
        col = col[0][None, None] * (1 - k) + col[1][None, None] * k
    u = (Y - t0) / (t1 - t0)                          # 0 at the upper edge, 1 at the lower
    aa = 1.5 / H
    m = np.clip((Y - t0) / aa, 0, 1) * np.clip((t1 - Y) / (aa * 6), 0, 1)
    uu = np.clip(u, 0, 1)
    # Across the ribbon: bright under the lit edge, dark in the body, a sheen fold, then falling into shadow.
    shade = 0.20 + 0.80 * np.exp(-uu / 0.10) + sheen * np.exp(-((uu - sheen_at) / 0.10) ** 2)
    c = (col if col.ndim == 3 else col[None, None]) * (shade * (1 - 0.55 * uu))[..., None]
    c += hi[None, None] * (np.exp(-((Y - t0) * H / 1.4) ** 2) * 0.85)[..., None]   # the rim line
    return c, m * alpha

def curve(y0, rise, amp, freq, ph, bend=1.6):
    return lambda t: y0 - rise * t ** bend + amp * np.sin(2 * np.pi * (freq * t + ph))

DEEP = hexc("#14207A"); VIOLET = hexc("#7A3FD6"); PLUM = hexc("#B24FB8")
layers = [
    (curve(0.70, 0.62, 0.040, 0.8, 0.15, 1.2), lambda t: 0.20 + 0.10 * t, (MIDNIGHT * 1.5, INDIGO * 0.7), GLACIER * 0.30, 0.75, 0.45, 0.25),
    (curve(0.84, 0.74, 0.050, 0.7, 0.60, 1.3), lambda t: 0.24 + 0.12 * t, (DEEP * 0.9, VIOLET * 0.75), GLACIER * 0.55, 0.78, 0.40, 0.35),
    (curve(0.98, 0.86, 0.055, 0.6, 0.35, 1.35), lambda t: 0.28 + 0.12 * t, (INDIGO * 0.9, PLUM * 0.85), ROSE * 0.55 + GLACIER * 0.40, 0.82, 0.33, 0.50),
    (curve(1.18, 0.86, 0.045, 0.55, 0.05, 1.4), lambda t: 0.40, (MIDNIGHT * 1.2, DEEP * 0.8), GLACIER * 0.45, 0.88, 0.50, 0.20),
]
rims = np.zeros((H, W, 3), np.float32)
for top, thick, col, hi, alpha, sa, sh in layers:
    c, a = ribbon(top, thick, col, hi, alpha, sa, sh)
    # The ribbon above casts a soft shadow onto what is behind it, just over its edge.
    t0 = top(x)
    d = t0 - Y
    img *= (1 - 0.35 * np.exp(-np.clip(d, 0, None) / 0.035) * (d > 0))[..., None]
    img = img * (1 - a[..., None]) + c * a[..., None]
    rims += hi[None, None] * (np.exp(-((Y - t0) * H / 2.0) ** 2) * a)[..., None]

img += blur(np.clip(rims, 0, 1), 22) * 0.55          # bloom off the rims
# Quiet top left for the clock and greeting, gentle vignette.
img *= (1 - 0.70 * np.exp(-((X / 0.48) ** 2 + (Y / 0.34) ** 2)))[..., None]
img *= (1 - 0.30 * np.clip((X - 0.5) ** 2 * 1.1 + (Y - 0.5) ** 2 * 1.5 - 0.06, 0, 1))[..., None]
rng = np.random.default_rng(7)
img += rng.normal(0, 1.1 / 255, img.shape).astype(np.float32)
out = Image.fromarray(np.clip(img * 255 + 0.5, 0, 255).astype(np.uint8))
out.save(sys.argv[1], quality=90, optimize=True, progressive=False, subsampling=0)
if len(sys.argv) > 2: out.resize((960, 540), Image.LANCZOS).save(sys.argv[2])
