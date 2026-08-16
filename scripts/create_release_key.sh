#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-library-release.jks}"
ALIAS="${LIBRARY_SIGNING_KEY_ALIAS:-library}"

if [[ -e "$OUT" ]]; then
  echo "Refusing to overwrite existing keystore: $OUT" >&2
  exit 1
fi

if [[ -z "${LIBRARY_SIGNING_STORE_PASSWORD:-}" ]]; then
  read -rsp "Keystore password: " LIBRARY_SIGNING_STORE_PASSWORD
  echo
fi
if [[ -z "${LIBRARY_SIGNING_KEY_PASSWORD:-}" ]]; then
  read -rsp "Key password (Enter to reuse keystore password): " LIBRARY_SIGNING_KEY_PASSWORD
  echo
  LIBRARY_SIGNING_KEY_PASSWORD="${LIBRARY_SIGNING_KEY_PASSWORD:-$LIBRARY_SIGNING_STORE_PASSWORD}"
fi

keytool -genkeypair \
  -keystore "$OUT" \
  -storepass "$LIBRARY_SIGNING_STORE_PASSWORD" \
  -alias "$ALIAS" \
  -keypass "$LIBRARY_SIGNING_KEY_PASSWORD" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=Library, OU=Android, O=garfbargle, C=CR"

chmod 600 "$OUT"
echo
echo "Created $OUT. Back it up offline before publishing a release."
echo "Signer certificate:"
keytool -list -v -keystore "$OUT" -storepass "$LIBRARY_SIGNING_STORE_PASSWORD" -alias "$ALIAS" | grep -E 'SHA256:|Alias name:' || true
