#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-library-distribution.jks}"
ALIAS="${LIBRARY_DISTRIBUTION_KEY_ALIAS:-library-distribution}"

if [[ -e "$OUT" ]]; then
  echo "Refusing to overwrite existing keystore: $OUT" >&2
  exit 1
fi

if [[ -z "${LIBRARY_DISTRIBUTION_STORE_PASSWORD:-}" ]]; then
  read -rsp "Distribution keystore password: " LIBRARY_DISTRIBUTION_STORE_PASSWORD
  echo
fi
if [[ -z "${LIBRARY_DISTRIBUTION_KEY_PASSWORD:-}" ]]; then
  read -rsp "Distribution key password (Enter to reuse keystore password): " LIBRARY_DISTRIBUTION_KEY_PASSWORD
  echo
  LIBRARY_DISTRIBUTION_KEY_PASSWORD="${LIBRARY_DISTRIBUTION_KEY_PASSWORD:-$LIBRARY_DISTRIBUTION_STORE_PASSWORD}"
fi

keytool -genkeypair \
  -keystore "$OUT" \
  -storepass "$LIBRARY_DISTRIBUTION_STORE_PASSWORD" \
  -alias "$ALIAS" \
  -keypass "$LIBRARY_DISTRIBUTION_KEY_PASSWORD" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=Library Managed Apps, OU=Android Distribution, O=garfbargle, C=CR"

chmod 600 "$OUT"
echo
echo "Created $OUT. Keep it separate from Library's own release key and back it up offline."
echo "Signer certificate:"
keytool -list -v -keystore "$OUT" -storepass "$LIBRARY_DISTRIBUTION_STORE_PASSWORD" -alias "$ALIAS" | grep -E 'SHA256:|Alias name:' || true
