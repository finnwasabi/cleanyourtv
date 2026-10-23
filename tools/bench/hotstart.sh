#!/bin/bash
# hotstart.sh <label>: 6 returns to Home from Settings, launch time as `am start -W` reports it (LaunchState HOT).
export ANDROID_SERIAL=192.168.11.42:5555
t=""
for i in 1 2 3 4 5 6; do
  adb shell am start -a android.settings.SETTINGS >/dev/null; sleep 4
  v=$(adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | grep -E "^TotalTime" | grep -oE "[0-9]+")
  t="$t $v"; sleep 3
done
echo "$1 hot start ms:$t"
