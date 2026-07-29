#!/usr/bin/env bash
#
# Development environment setup for the Audiobookshelf mobile app on macOS.
# Installs the toolchain for building the Android and iOS apps:
#   Homebrew, Node.js 20, OpenJDK 17, Android SDK (cmdline tools + API 35),
#   CocoaPods, project npm dependencies and Capacitor native projects.
#
# Xcode itself must be installed from the App Store (the script checks for it).
# The script is idempotent - safe to re-run.
#
# Usage: ./scripts/setup-macos-dev.sh [--no-ios] [--no-android]

set -euo pipefail

INSTALL_IOS=1
INSTALL_ANDROID=1
for arg in "$@"; do
  case "$arg" in
    --no-ios) INSTALL_IOS=0 ;;
    --no-android) INSTALL_ANDROID=0 ;;
    *) echo "Unknown option: $arg" && exit 1 ;;
  esac
done

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARN: %s\033[0m\n' "$*"; }

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script is for macOS only." && exit 1
fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

# ---------------------------------------------------------------- Homebrew
if ! command -v brew >/dev/null 2>&1; then
  log "Installing Homebrew"
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  # Add brew to PATH for this shell (Apple Silicon vs Intel)
  if [[ -x /opt/homebrew/bin/brew ]]; then eval "$(/opt/homebrew/bin/brew shellenv)"; fi
  if [[ -x /usr/local/bin/brew ]]; then eval "$(/usr/local/bin/brew shellenv)"; fi
else
  log "Homebrew already installed"
fi

# ---------------------------------------------------------------- Node.js 20
if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | cut -d. -f1 | tr -d v)" -lt 20 ]]; then
  log "Installing Node.js 20"
  brew install node@20
  brew link --overwrite node@20
else
  log "Node.js $(node -v) already installed"
fi

# ---------------------------------------------------------------- Android
if [[ $INSTALL_ANDROID -eq 1 ]]; then
  log "Installing OpenJDK 17 (required by the Android Gradle build)"
  brew list openjdk@17 >/dev/null 2>&1 || brew install openjdk@17
  JAVA_HOME_17="$(brew --prefix openjdk@17)"
  export JAVA_HOME="$JAVA_HOME_17"

  log "Installing Android command line tools"
  brew list --cask android-commandlinetools >/dev/null 2>&1 || brew install --cask android-commandlinetools

  ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  export ANDROID_HOME
  mkdir -p "$ANDROID_HOME"

  SDKMANAGER="$(brew --prefix)/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager"
  if [[ ! -x "$SDKMANAGER" ]]; then
    SDKMANAGER="$(command -v sdkmanager || true)"
  fi
  if [[ -x "$SDKMANAGER" ]]; then
    log "Installing Android SDK packages (API 35)"
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
    "$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
      "platform-tools" \
      "platforms;android-35" \
      "build-tools;35.0.0" \
      "emulator" >/dev/null
  else
    warn "sdkmanager not found - install SDK 35 via Android Studio (SDK Manager)"
  fi

  log "Installing Android Studio (IDE, optional but recommended)"
  brew list --cask android-studio >/dev/null 2>&1 || brew install --cask android-studio

  # Persist environment for future shells
  SHELL_RC="$HOME/.zshrc"
  if ! grep -q "ANDROID_HOME" "$SHELL_RC" 2>/dev/null; then
    log "Adding ANDROID_HOME and JAVA_HOME to $SHELL_RC"
    {
      echo ''
      echo '# Audiobookshelf app dev environment'
      echo "export ANDROID_HOME=\"$ANDROID_HOME\""
      echo "export JAVA_HOME=\"$JAVA_HOME_17\""
      echo 'export PATH="$ANDROID_HOME/platform-tools:$PATH"'
    } >> "$SHELL_RC"
  fi
fi

# ---------------------------------------------------------------- iOS
if [[ $INSTALL_IOS -eq 1 ]]; then
  log "Checking Xcode"
  if ! xcode-select -p >/dev/null 2>&1; then
    warn "Xcode command line tools missing - triggering install (rerun the script afterwards)"
    xcode-select --install || true
  fi
  if [[ ! -d /Applications/Xcode.app ]]; then
    warn "Xcode.app not found - install it from the App Store to build the iOS app"
  fi

  log "Installing CocoaPods"
  brew list cocoapods >/dev/null 2>&1 || brew install cocoapods
fi

# ---------------------------------------------------------------- Project
log "Installing npm dependencies"
npm ci

log "Syncing Capacitor native projects"
npx cap sync android || warn "cap sync android failed - check the Android SDK setup"
if [[ $INSTALL_IOS -eq 1 && -d /Applications/Xcode.app ]]; then
  npx cap sync ios || warn "cap sync ios failed - open ios/App and run 'pod install' manually"
fi

log "Done"
cat <<'EOT'

Next steps:
  npm run dev                 # web dev server (browser)
  npx cap open android        # open in Android Studio, run on device/emulator
  npx cap open ios            # open in Xcode, run on device/simulator

Android live-reload workflow (see readme.md):
  npm run dev & npx cap run android -l --external
EOT
