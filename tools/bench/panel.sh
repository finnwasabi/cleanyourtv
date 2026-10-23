#!/bin/bash
# panel.sh <label>: 6 openings of the quick settings panel with the remote's gear (keycode 83, settings_key_keycodes)
# over Home, launch time from the events log as in coldstart.sh; Back closes it each time.
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TV_HOST:?set TV_HOST to the TV IP}:5555}"
adb shell input keyevent HOME; sleep 3
t=""
for i in 1 2 3 4 5 6; do
  adb logcat -b events -c; adb shell input keyevent 83; sleep 2
  v=$(adb logcat -d -b events | grep "wm_activity_launch_time" | grep QuickSettings | tail -1 | grep -oE ",[0-9]+\]$" | tr -d ',]')
  t="$t $v"
  adb shell input keyevent BACK; sleep 2
done
echo "$1 panel open ms:$t"
