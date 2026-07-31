#!/usr/bin/env bash
# Launch the Android Auto Desktop Head Unit (DHU) from WSL against the phone
# connected over (wireless) adb - for testing the Android Auto browse tree
# without a car. See docs/native-tts-player-design.md (A.9).
#
# One-time phone setup: Android Auto settings -> tap "Version" 10x to enable
# developer mode -> three-dot menu -> "Start head unit server". The server
# must be started again after each phone reboot.
set -euo pipefail

DHU_DIR="$HOME/Android/Sdk/extras/google/auto"
ADB="$HOME/Android/Sdk/platform-tools/adb"

# libc++/libunwind for the DHU binary, extracted from debs into the user dir
# because sudo is interactive-only in this WSL setup
export LD_LIBRARY_PATH="$HOME/dhu-libs/usr/lib/x86_64-linux-gnu:$HOME/dhu-libs/usr/lib/llvm-14/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# WSLg is disabled in .wslconfig (guiApplications=false), the GUI goes to the
# X410 server on the Windows host instead - its IP is the WSL default gateway.
# Software GL because llvmpipe renders client-side and works over TCP X11.
if [ -z "${DISPLAY:-}" ]; then
  DISPLAY="$(ip route show default | awk '{print $3; exit}'):0.0"
  export DISPLAY
fi
export LIBGL_ALWAYS_SOFTWARE=1

"$ADB" forward tcp:5277 tcp:5277
exec "$DHU_DIR/desktop-head-unit" "$@"
