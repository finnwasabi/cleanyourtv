# Per-frame vertical shift of the scrolling area (rows 30..135 of a 240x135 gray video), by best match.
import sys, numpy as np
w, h = 240, 135
data = np.fromfile(sys.argv[1], dtype=np.uint8)
n = data.size // (w * h)
f = data[: n * w * h].reshape(n, h, w).astype(np.int16)
top = 32  # below the top bar
out = []
for i in range(1, n):
    a, b = f[i - 1, top:, 20:220], f[i, top:, 20:220]
    best, bd = 0, None
    for dy in range(-60, 61):
        if dy >= 0:
            x, y = a[dy:], b[: a.shape[0] - dy]
        else:
            x, y = a[: a.shape[0] + dy], b[-dy:]
        if x.shape[0] < 30: continue
        d = np.abs(x - y).mean()
        if bd is None or d < bd: bd, best = d, dy
    out.append((i, best, bd))
for i, dy, d in out:
    if dy != 0: print(f"frame {i:4d} t={i/30:5.2f}s dy={dy:+3d} (x{1080/135:.0f} = {dy*8:+4d}px) diff={d:.1f}")
