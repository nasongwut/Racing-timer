#!/bin/bash
# Speed Timer — one-time developer setup for macOS.
# Installs everything needed to build the app from the command line:
#   Homebrew, JDK 17, Gradle, Android command-line tools, adb,
#   Android SDK (platform 35, build-tools 35.0.0, platform-tools),
#   then downloads all Gradle / AndroidX / Compose libraries with a warm-up build.
#
# Usage:
#   ./install-dev.sh              full setup + warm-up build
#   ./install-dev.sh --no-build   install tools only
#   source ./install-dev.sh --env only export JAVA_HOME / ANDROID_HOME (used by build.sh)

MODE="${1:-}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

_brew_env() {
  if ! command -v brew >/dev/null 2>&1; then
    for b in /opt/homebrew/bin/brew /usr/local/bin/brew; do
      [ -x "$b" ] && eval "$("$b" shellenv)" && break
    done
  fi
}

_export_env() {
  _brew_env
  export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
  export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
}

# ---- env only (when sourced by build.sh) ---------------------------------
if [ "$MODE" = "--env" ]; then
  _export_env
  echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"
  return 0 2>/dev/null || exit 0
fi

set -e
step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }

# 1) Homebrew
step "Homebrew"
_brew_env
if ! command -v brew >/dev/null 2>&1; then
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  _brew_env
fi
brew --version | head -1

# 2) Toolchain
step "JDK 17, Gradle, Android command-line tools, platform-tools (adb)"
brew list openjdk@17 >/dev/null 2>&1 || brew install openjdk@17
brew list gradle     >/dev/null 2>&1 || brew install gradle
brew list --cask android-commandlinetools >/dev/null 2>&1 || brew install --cask android-commandlinetools
brew list --cask android-platform-tools  >/dev/null 2>&1 || brew install --cask android-platform-tools
_export_env
java -version 2>&1 | head -1

# 3) Android SDK packages
step "Android SDK (platform 35, build-tools 35.0.0, platform-tools)"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"

# 4) Gradle wrapper
step "Gradle wrapper 8.9"
cd "$PROJECT_DIR"
[ -f gradlew ] || gradle wrapper --gradle-version 8.9 -q
chmod +x gradlew build.sh 2>/dev/null || true

# 5) Shell profile (so adb / java work in every new terminal)
step "Shell profile (~/.zprofile)"
PROFILE="$HOME/.zprofile"
MARK="# >>> speedtimer android env >>>"
if ! grep -q "$MARK" "$PROFILE" 2>/dev/null; then
  cat >> "$PROFILE" <<PROF

$MARK
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="\$ANDROID_HOME"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$PATH"
# <<< speedtimer android env <<<
PROF
  echo "added Android/Java env to $PROFILE (open a new terminal to use it)"
else
  echo "already configured"
fi

# 6) Download all libraries (AGP, Kotlin, AndroidX, Compose) via a warm-up build
if [ "$MODE" != "--no-build" ]; then
  step "Downloading Gradle dependencies (first run may take several minutes)"
  ./gradlew --no-daemon assembleDebug -q
  echo "warm-up build OK: app/build/outputs/apk/debug/app-debug.apk"
fi

step "Done"
echo "Build:            ./build.sh"
echo "Build + install:  ./build.sh install"
echo "Build + run:      ./build.sh run"
