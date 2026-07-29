# shellcheck shell=bash disable=SC2317
# Source this to set up JDK 17 and Android SDK paths for local builds:
#   source scripts/android-env.sh
# Respects existing JAVA_HOME/ANDROID_HOME; otherwise probes common locations.

_first_existing() {
  for candidate in "$@"; do
    if [ -d "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17'; then
  JAVA_HOME="$(_first_existing \
    /usr/lib/jvm/temurin-17-jdk \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/java-17-temurin-jdk \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home)" || {
    echo "android-env: no JDK 17 found; install one and set JAVA_HOME" >&2
    return 1 2>/dev/null || exit 1
  }
  export JAVA_HOME
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  ANDROID_HOME="$(_first_existing \
    "$HOME/Android/Sdk" \
    "$HOME/Library/Android/sdk")" || {
    echo "android-env: no Android SDK found; install one and set ANDROID_HOME" >&2
    return 1 2>/dev/null || exit 1
  }
  export ANDROID_HOME
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
echo "android-env: JAVA_HOME=$JAVA_HOME"
echo "android-env: ANDROID_HOME=$ANDROID_HOME"
