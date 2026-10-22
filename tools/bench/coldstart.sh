#!/bin/bash
# coldstart.sh <label>: 6 cold starts of Home (force-stop; the system relaunches Home), launch time from the
# events log; then PSS after 3 tours.
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TV_HOST:?set TV_HOST to the TV IP}:5555}"
S="$(cd "$(dirname "$0")" && pwd)"; OUT_DIR="${OUT_DIR:-/tmp/tvhome-bench}"; mkdir -p "$OUT_DIR"
t=""
for i in 1 2 3 4 5 6; do
  adb logcat -b all -c; adb shell am force-stop com.gathin.tvhome; sleep 6
  v=$(adb logcat -d -b events | grep "wm_activity_launch_time" | grep gathin | tail -1 | grep -oE ",[0-9]+\]$" | tr -d ',]')
  t="$t $v"
done
echo "$1 cold start ms:$t"
for r in 1 2 3; do bash $S/tour.sh cs >/dev/null; done
adb shell input keyevent HOME; sleep 4
adb shell "dumpsys meminfo com.gathin.tvhome | grep -E 'TOTAL PSS|Native Heap:'" | tr -s ' ' | tr '\n' ';'; echo
