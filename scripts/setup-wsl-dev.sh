#!/usr/bin/env bash
#
# Development environment setup for the Audiobookshelf mobile app on
# WSL (Windows Subsystem for Linux, Ubuntu/Debian based distro).
# Installs the Android toolchain: Node.js 20, OpenJDK 21, Android SDK
# (cmdline tools + API 35), project npm dependencies and the Capacitor
# Android project sync.
#
# The build needs compile SDK 35 regardless of the Android version of the
# target phone - the app runs on any device with Android 7+ (minSdk 24).
#
# The script is idempotent - safe to re-run.
#
# Usage: ./scripts/setup-wsl-dev.sh

set -euo pipefail

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARN: %s\033[0m\n' "$*"; }

if ! grep -qi microsoft /proc/version 2>/dev/null; then
  warn "This does not look like WSL - continuing anyway (script works on plain Ubuntu/Debian too)"
fi
if ! command -v apt-get >/dev/null 2>&1; then
  echo "This script requires an apt-based distro (Ubuntu/Debian WSL)." && exit 1
fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

# ---------------------------------------------------------------- base packages
log "Installing base packages (curl, unzip, git, OpenJDK 21)"
sudo apt-get update
sudo apt-get install -y curl wget unzip zip git openjdk-21-jdk-headless

JAVA_HOME_21="/usr/lib/jvm/java-21-openjdk-amd64"
export JAVA_HOME="$JAVA_HOME_21"

# ---------------------------------------------------------------- Node.js 20
if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | cut -d. -f1 | tr -d v)" -lt 20 ]]; then
  log "Installing Node.js 20 (NodeSource repository)"
  curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
  sudo apt-get install -y nodejs
else
  log "Node.js $(node -v) already installed"
fi

# ---------------------------------------------------------------- Android SDK
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$SDKMANAGER" ]]; then
  log "Installing Android command line tools"
  CMDTOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
  TMP_DIR="$(mktemp -d)"
  wget -q "https://dl.google.com/android/repository/$CMDTOOLS_ZIP" -O "$TMP_DIR/$CMDTOOLS_ZIP"
  unzip -q "$TMP_DIR/$CMDTOOLS_ZIP" -d "$TMP_DIR"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$TMP_DIR/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$TMP_DIR"
else
  log "Android command line tools already installed"
fi

log "Installing Android SDK packages (API 35)"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" >/dev/null

# ---------------------------------------------------------------- shell env
SHELL_RC="$HOME/.bashrc"
# migrate JAVA_HOME from the JDK 17 this script used to install
sed -i 's|export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"|export JAVA_HOME="'"$JAVA_HOME_21"'"|' "$SHELL_RC" 2>/dev/null || true
if ! grep -q "ANDROID_HOME" "$SHELL_RC" 2>/dev/null; then
  log "Adding ANDROID_HOME and JAVA_HOME to $SHELL_RC"
  {
    echo ''
    echo '# Audiobookshelf app dev environment'
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export JAVA_HOME=\"$JAVA_HOME_21\""
    echo 'export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"'
  } >> "$SHELL_RC"
fi

# ---------------------------------------------------------------- project
log "Installing npm dependencies"
npm ci

log "Syncing Capacitor Android project"
npx cap sync android || warn "cap sync android failed - check the Android SDK setup"

log "Done"
cat <<'EOT'

Build the app:
  npm run generate                # build the web assets
  npx cap sync android
  cd android && ./gradlew assembleDebug
  # APK: android/app/build/outputs/apk/debug/app-debug.apk

Connecting a physical phone (e.g. Pixel) from WSL - two options:

  A) Wireless debugging (simplest, no Windows-side setup):
     On the phone: Settings -> Developer options -> Wireless debugging -> Pair
     In WSL:  adb pair <ip>:<pair-port>   (enter the pairing code)
              adb connect <ip>:<port>
              adb devices

  B) USB passthrough via usbipd-win (run in Windows PowerShell as admin):
     winget install usbipd
     usbipd list
     usbipd bind --busid <busid>
     usbipd attach --wsl --busid <busid>
     Then `adb devices` in WSL sees the USB device.

Install and run on the phone:
  adb install -r android/app/build/outputs/apk/debug/app-debug.apk

Note: the Android emulator inside WSL needs nested virtualization (Windows 11)
and is not installed by this script - a physical device is the smoother path.
EOT
