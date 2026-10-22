# Bench scripts (Wanbo X7, wanbo-x7 branch)

The measurements in the top README were taken with these, over ADB on the TV at `$TV_HOST`, port 5555. Output goes to
`$OUT_DIR` (default `/tmp/tvhome-bench`).

| Script | What it measures |
|---|---|
| `measure.sh <package> <label>` | One minute idle on Home, then free memory, the launcher's PSS, idle CPU, and `gfxinfo` over 30 key presses (Right 4, Left 4, Down, Up, three times). The Projectivy comparison. |
| `tour.sh <label> [record]` | Home, Down 9, Up 9 at 0.7 s; `gfxinfo` summary. With `record`, a screen recording and `shift.py`'s per-frame scroll jumps. |
| `coldstart.sh <label>` | Six cold starts (force-stop, Android relaunches Home), launch time from `wm_activity_launch_time`, then PSS after three tours. |
| `gcrun.sh <label>` | Three tours with jank, native heap and PSS after each, and the GCs the launcher logged. |
| `fs.py <framestats.txt>` | Medians of a `dumpsys gfxinfo <pkg> framestats` dump: total, issue, GPU, dequeue. |
| `shift.py <raw video>` | Per-frame vertical shift of a 240x135 grey video, for finding jumps in a scroll. |
