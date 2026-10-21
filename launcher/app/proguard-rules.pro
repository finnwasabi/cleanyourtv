# This fork renames too: R8 lays the DEX out in startup order only for an obfuscated build (with
# android.experimental.r8.dex-startup-optimization), and short names make the APK smaller. Stack traces
# are read back with the build's mapping.txt, kept next to each APK.
