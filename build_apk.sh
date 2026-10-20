#!/usr/bin/env bash
# One-command build for DocScan.  Usage:  ./build_apk.sh
# Installs whatever is missing (Android SDK command-line tools, SDK platform, Gradle wrapper)
# and produces DocScan.apk in this folder. Needs Java 17–22 and an internet connection (first run only).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

say() { printf "\n\033[1;34m==> %s\033[0m\n" "$*"; }
die() { printf "\n\033[1;31mERROR: %s\033[0m\n" "$*" >&2; exit 1; }

OS="$(uname -s)"
case "$OS" in
  Darwin) TOOLS_OS="mac" ;;
  Linux)  TOOLS_OS="linux" ;;
  *) die "Unsupported OS: $OS (use Android Studio instead)" ;;
esac

# ---------------------------------------------------------------- 1. Java 17–22 (Gradle 8.9 runtime support)
java_major() {
  "$1" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/'
}
java_compatible() {
  local major
  major="$(java_major "$1")"
  [ "$major" -ge 17 ] 2>/dev/null && [ "$major" -le 22 ] 2>/dev/null
}
find_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && java_compatible "$JAVA_HOME/bin/java"; then
    echo "$JAVA_HOME"; return 0
  fi
  if [ "$OS" = "Darwin" ]; then
    local c
    if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
      c="$(/usr/libexec/java_home -v 17)"
      if [ -x "$c/bin/java" ] && java_compatible "$c/bin/java"; then echo "$c"; return 0; fi
    fi
    for c in "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
             "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; do
      if [ -x "$c/bin/java" ] && java_compatible "$c/bin/java"; then echo "$c"; return 0; fi
    done
  fi
  local c
  for c in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"; do
    if [ -x "$c/bin/java" ] && java_compatible "$c/bin/java"; then echo "$c"; return 0; fi
  done
  if [ "$OS" = "Darwin" ] && /usr/libexec/java_home -v 17+ >/dev/null 2>&1; then
    c="$(/usr/libexec/java_home -v 17+)"
    if [ -x "$c/bin/java" ] && java_compatible "$c/bin/java"; then echo "$c"; return 0; fi
  fi
  if command -v java >/dev/null 2>&1 && java_compatible "$(command -v java)"; then
    dirname "$(dirname "$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)")"; return 0
  fi
  return 1
}

if ! JH="$(find_java_home)"; then
  if command -v brew >/dev/null 2>&1; then
    say "Installing Java 17 (Temurin) with Homebrew"
    brew install --cask temurin@17 || die "Homebrew could not install Java. Install JDK 17 from https://adoptium.net and re-run."
    JH="$(find_java_home)" || die "Java 17 was installed but not found. Open a new terminal and re-run."
  else
    die "Java 17–22 is required for Gradle 8.9. Install JDK 17 from https://adoptium.net (or install Android Studio) and re-run."
  fi
fi
export JAVA_HOME="$JH"
export PATH="$JAVA_HOME/bin:$PATH"
say "Using Java at $JAVA_HOME"

# ---------------------------------------------------------------- 2. Android SDK
sdk_ok() { [ -n "${1:-}" ] && [ -d "$1" ]; }
SDK=""
for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$ROOT/.android-sdk"; do
  if sdk_ok "$c"; then SDK="$c"; break; fi
done
[ -n "$SDK" ] || SDK="$ROOT/.android-sdk"
mkdir -p "$SDK"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
say "Android SDK: $SDK"

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  say "Downloading Android command-line tools"
  TMP="$(mktemp -d)"
  curl -fL --progress-bar -o "$TMP/cmdline.zip" \
    "https://dl.google.com/android/repository/commandlinetools-${TOOLS_OS}-11076708_latest.zip" \
    || die "Could not download the Android command-line tools. Check your internet connection."
  unzip -q "$TMP/cmdline.zip" -d "$TMP"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TMP/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$TMP"
fi

if [ ! -d "$SDK/platforms/android-34" ] || [ ! -d "$SDK/build-tools/34.0.0" ] || [ ! -d "$SDK/platform-tools" ]; then
  say "Installing Android platform 34 and build tools (about 150 MB, first run only)"
  set +o pipefail
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  set -o pipefail
  "$SDKMANAGER" "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
    || die "SDK installation failed."
fi
printf "sdk.dir=%s\n" "$SDK" > "$ROOT/local.properties"

# ---------------------------------------------------------------- 3. Gradle wrapper
if [ ! -f "$ROOT/gradlew" ] || [ ! -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]; then
  say "Setting up Gradle 8.9"
  TMP="$(mktemp -d)"
  curl -fL --progress-bar -o "$TMP/gradle.zip" "https://services.gradle.org/distributions/gradle-8.9-bin.zip" \
    || die "Could not download Gradle. Check your internet connection."
  unzip -q "$TMP/gradle.zip" -d "$TMP"
  mkdir -p "$TMP/proj" && touch "$TMP/proj/settings.gradle"
  "$TMP/gradle-8.9/bin/gradle" -p "$TMP/proj" wrapper --gradle-version 8.9 --distribution-type bin -q
  mkdir -p "$ROOT/gradle/wrapper"
  cp "$TMP/proj/gradlew" "$TMP/proj/gradlew.bat" "$ROOT/"
  cp "$TMP/proj/gradle/wrapper/gradle-wrapper.jar" "$ROOT/gradle/wrapper/"
  rm -rf "$TMP"
fi
chmod +x "$ROOT/gradlew"

# ---------------------------------------------------------------- 4. Build
say "Building the APK (first build downloads dependencies; takes a few minutes)"
"$ROOT/gradlew" --no-daemon assembleRelease

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "Build finished but the APK was not found."
cp "$APK" "$ROOT/DocScan.apk"

say "Done!  APK: $ROOT/DocScan.apk"
cat <<EOF

Install on your phone:
  - Send DocScan.apk to the phone (AirDrop is not available for Android; use USB, Google Drive, email or WhatsApp-to-self),
    open it, and allow "Install unknown apps" when asked.
  - Or, with USB debugging on:  "$SDK/platform-tools/adb" install -r "$ROOT/DocScan.apk"
EOF
