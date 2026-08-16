#!/usr/bin/env sh
set -eu

# Tiny self-contained bootstrap for this initial repository. Replace with the
# stock Gradle wrapper any time with: gradle wrapper --gradle-version 9.5.0
VERSION=9.5.0
ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/library-bootstrap"
HOME_DIR="$ROOT/gradle-$VERSION"
ZIP="$ROOT/gradle-$VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"

if [ ! -x "$HOME_DIR/bin/gradle" ]; then
  mkdir -p "$ROOT"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "Library needs curl or wget once to bootstrap Gradle $VERSION." >&2
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$ROOT"
  rm -f "$ZIP"
fi

exec "$HOME_DIR/bin/gradle" "$@"
