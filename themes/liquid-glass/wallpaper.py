# Liquid glass wallpaper: on deep black, the edge of two large glass discs rising from the lower right. Each disc
# glows along its rim (Fresnel: brightest at the edge, fading inward), with a faint colour split like real glass,
# and the colour turns slowly around the rim. Light adds where they overlap and is tone mapped, so it glows instead
# of clipping. The top left stays black for the clock. Deterministic.
# Usage: python3 wallpaper.py wallpaper.jpg [preview.png] (needs numpy and Pillow).
import sys, numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080
x = np.linspace(0, W / H, W, dtype=np.float32)[None, :]   # square pixels: x runs 0..1.78
y = np.linspace(0, 1, H, dtype=np.float32)[:, None]
X = np.broadcast_to(x, (H, W)); Y = np.broadcast_to(y, (H, W))
def hexc(h): return np.array([int(h[i:i+2], 16) / 255 for i in (1, 3, 5)], np.float32)
def blur(a, r):
    if a.ndim == 3: return np.stack([blur(a[..., i], r) for i in range(3)], -1)
    m = a.max() or 1
    im = Image.fromarray(np.clip(a / m * 255, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r))
    return np.asarray(im, np.float32) / 255 * m

light = np.zeros((H, W, 3), np.float32)
veil = np.ones((H, W), np.float32)          # how much of what is behind a disc shows through it

def disc(cx, cy, R, rim, glow, cols, power, split, body):
    global light, veil
    dx, dy = X - cx, Y - cy
    r = np.sqrt(dx * dx + dy * dy)
    th = np.arctan2(dy, dx)                  # angle round the rim
    k = np.clip((th - th.min()) / (np.ptp(th) + 1e-6), 0, 1)
    # Colour round the rim: a smooth walk through the given stops.
    stops = np.linspace(0, 1, len(cols))
    col = np.stack([np.interp(k, stops, [c[i] for c in cols]) for i in range(3)], -1)
    for ch, off in ((0, split), (1, 0.0), (2, -split)):
        d = r - (R + off)
        inner = np.where(d < 0, np.exp(d / rim), 0)            # Fresnel: bright at the edge, fading in
        outer = np.where(d >= 0, np.exp(-d / glow) * 0.35, 0)  # a soft halo outside
        light[..., ch] += power * col[..., ch] * (inner + outer)
    inside = np.clip(-(r - R) * H / 2, 0, 1)
    veil = veil * (1 - body * inside)

INK = hexc("#000000")
BLUE, AZURE, VIOLET, ORCHID, ROSE, AMBER = map(hexc, ["#1E3BFF", "#3D8BFF", "#7B4DFF", "#C04BFF", "#FF5FA8", "#FFB36B"])
# Back disc: big, cool, quiet. Front disc: smaller, warmer, brighter. Centres off screen at the lower right.
disc(1.95, 1.55, 1.30, 0.050, 0.10, [AZURE, BLUE, VIOLET], 0.60, 0.0015, 0.25)
disc(1.62, 1.45, 0.98, 0.034, 0.08, [VIOLET, ORCHID, ROSE], 0.80, 0.0018, 0.30)

# What the glass refracts: a deep, dim gradient behind everything, mostly at the lower right.
base = np.zeros((H, W, 3), np.float32)
base += (hexc("#0A0F3A") * 1.0)[None, None] * np.exp(-(((X - 1.55) / 0.75) ** 2 + ((Y - 1.05) / 0.60) ** 2))[..., None]
base += (hexc("#2A0D3A") * 0.9)[None, None] * np.exp(-(((X - 1.25) / 0.45) ** 2 + ((Y - 1.10) / 0.35) ** 2))[..., None]
img = base * (0.55 + 0.45 * veil[..., None]) + light
img += blur(light, 46) * 0.30                               # bloom
img = 1 - np.exp(-img * 1.20)                               # tone map: glow without clipping
# Black top left for the clock, and a soft vignette.
img *= (1 - 0.85 * np.exp(-((X / 0.95) ** 2 + (Y / 0.55) ** 2)))[..., None]
img = np.clip(img, 0, 1) ** (1 / 1.08)
rng = np.random.default_rng(11)
img += rng.normal(0, 1.0 / 255, img.shape).astype(np.float32)
out = Image.fromarray(np.clip(img * 255 + 0.5, 0, 255).astype(np.uint8))
out.save(sys.argv[1], quality=92, optimize=True, progressive=False, subsampling=0)
if len(sys.argv) > 2: out.resize((960, 540), Image.LANCZOS).save(sys.argv[2])
