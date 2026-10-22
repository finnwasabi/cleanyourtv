#!/bin/bash
# Usage: measure.sh <launcher package> <label>
PKG=$1; L=$2; export ANDROID_SERIAL="${ANDROID_SERIAL:-${TV_HOST:?set TV_HOST to the TV IP}:5555}"
S="$(cd "$(dirname "$0")" && pwd)"; OUT_DIR="${OUT_DIR:-/tmp/tvhome-bench}"; mkdir -p "$OUT_DIR"
OUT=$OUT_DIR/measure-$L.txt; : > $OUT
if [ -n "$START" ]; then adb shell am start -n $START >/dev/null; else adb shell input keyevent HOME; fi; sleep 60
echo "== MemAvailable (3 mẫu, cách 10s)" >> $OUT
for i in 1 2 3; do adb shell "grep MemAvailable /proc/meminfo" >> $OUT; sleep 10; done
echo "== PSS launcher" >> $OUT
adb shell "dumpsys meminfo $PKG | grep -E 'TOTAL PSS|TOTAL:' | head -2" >> $OUT
for w in tv.projectivy.plugin.wallpaperprovider.overflight; do adb shell "dumpsys meminfo $w 2>/dev/null | grep -E 'TOTAL PSS|TOTAL:' | head -1" | sed "s/^/[$w] /" >> $OUT; done
echo "== CPU lúc rảnh (top 3 lần, 5s)" >> $OUT
adb shell "top -b -n 3 -d 5 -o %CPU,NAME -s 1 | grep -E '^ *[0-9.]+ ' | grep -vE '^ *0\.0 ' | head -40" >> $OUT
adb shell "top -b -n 1 | grep -E '^[0-9]+%cpu'" >> $OUT
echo "== D-pad gfxinfo" >> $OUT
adb shell dumpsys gfxinfo $PKG reset >/dev/null
for r in 1 2 3; do
  for k in RIGHT RIGHT RIGHT RIGHT LEFT LEFT LEFT LEFT DOWN UP; do adb shell input keyevent DPAD_$k; sleep 0.35; done
done
sleep 1
adb shell "dumpsys gfxinfo $PKG | grep -E 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI thread|Number Slow bitmap|Number Slow issue draw' | head -12" >> $OUT
true
cat $OUT
