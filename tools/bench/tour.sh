#!/bin/bash
# tour.sh <label> [record]: Home, Down 9x to the bottom, Up 9x back, 0.7s apart.
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TV_HOST:?set TV_HOST to the TV IP}:5555}"
S="$(cd "$(dirname "$0")" && pwd)"; OUT_DIR="${OUT_DIR:-/tmp/tvhome-bench}"; mkdir -p "$OUT_DIR"
L=$1; mkdir -p $OUT_DIR/tour
adb shell input keyevent HOME; sleep 3
adb shell dumpsys gfxinfo com.gathin.tvhome reset >/dev/null
if [ -n "$2" ]; then adb shell "screenrecord --time-limit 24 --bit-rate 6000000 --size 960x540 /sdcard/$L.mp4" & sleep 2; fi
for i in $(seq 1 9); do adb shell input keyevent DPAD_DOWN; sleep 0.7; done
sleep 1
for i in $(seq 1 9); do adb shell input keyevent DPAD_UP; sleep 0.7; done
sleep 2
adb shell "dumpsys gfxinfo com.gathin.tvhome | grep -E 'Total frames|Janky frames:|90th percentile|99th percentile|Slow UI|Slow issue'"
if [ -n "$2" ]; then wait; adb pull /sdcard/$L.mp4 $OUT_DIR/tour/$L.mp4 >/dev/null; adb shell rm /sdcard/$L.mp4
  ffmpeg -v quiet -y -i $OUT_DIR/tour/$L.mp4 -vf "fps=30,scale=240:135,format=gray" -f rawvideo $OUT_DIR/tour/$L.raw
  python3 $S/shift.py $OUT_DIR/tour/$L.raw | awk '{v=$4; sub(/dy=/,"",v); if (v+0 < -12 || v+0 > 12) print}'; fi
