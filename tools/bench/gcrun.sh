#!/bin/bash
# gcrun.sh <label>: 3 tours; jank per tour, GCs the launcher logged, PSS and native heap range seen after each.
export ANDROID_SERIAL=192.168.11.42:5555
S="$(cd "$(dirname "$0")" && pwd)"; OUT_DIR="${OUT_DIR:-/tmp/tvhome-bench}"; mkdir -p "$OUT_DIR"
pid=$(adb shell pidof com.gathin.tvhome); adb logcat -c
for r in 1 2 3; do
  bash $S/tour.sh g | grep -E "Janky frames:|99th" | tr '\n' ' '
  adb shell "dumpsys meminfo com.gathin.tvhome | grep -E 'Native Heap:|TOTAL PSS'" | tr -s ' ' | tr '\n' ' '; echo
done
echo "$1 GCs: $(adb logcat -d --pid=$pid | grep -ciE 'GC freed')"
