#!/usr/bin/env bash
set -u

REPO="${AICLAW_REPO:-qwertyuiop982/aiclaw}"
VERSION="${AICLAW_VERSION:-latest}"
PREFIX="${AICLAW_PREFIX:-${HOME}/.local}"
BIN_DIR="${PREFIX}/bin"
LIB_DIR="${PREFIX}/lib/aiclaw"
BASE_URL="https://github.com/${REPO}/releases"

fail() { printf 'aiclaw install: %s\n' "$*" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v tar >/dev/null 2>&1 || fail "tar is required"
command -v java >/dev/null 2>&1 || fail "Java 17 or newer is required"

JAVA_MAJOR="$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n1)"
[ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge 17 ] 2>/dev/null || fail "Java 17 or newer is required"

if [ "$VERSION" = latest ]; then
  RELEASE_URL="${BASE_URL}/latest/download"
  ASSET_NAME="aiclaw.tar.gz"
else
  RELEASE_URL="${BASE_URL}/download/${VERSION}"
  ASSET_NAME="aiclaw-${VERSION#v}.tar.gz"
fi

TMP="$(mktemp -d 2>/dev/null || mktemp -d -t aiclaw)"
trap 'rm -rf "$TMP"' EXIT
ARCHIVE="${TMP}/aiclaw.tar.gz"

printf 'Downloading aiclaw (%s) from %s\n' "$VERSION" "$RELEASE_URL"
curl --fail --location --retry 3 --connect-timeout 15 \
  "${RELEASE_URL}/${ASSET_NAME}" -o "$ARCHIVE" \
  || fail "unable to download release asset"

mkdir -p "$BIN_DIR" "$LIB_DIR" || fail "cannot create install directory"
tar -xzf "$ARCHIVE" -C "$TMP" || fail "invalid release archive"
SOURCE_DIR="$(find "$TMP" -maxdepth 2 -type f -name 'aiclaw-*.jar' -printf '%h\n' 2>/dev/null | head -n1)"
[ -n "$SOURCE_DIR" ] || SOURCE_DIR="$TMP"
JAR="$(find "$SOURCE_DIR" -maxdepth 1 -type f -name '*.jar' | head -n1)"
[ -n "$JAR" ] || fail "release archive contains no JAR"

cp "$JAR" "$LIB_DIR/aiclaw.jar" || fail "cannot install JAR"
cat > "$BIN_DIR/aiclaw" <<EOF
#!/usr/bin/env bash
exec "${JAVA:-java}" -jar "${LIB_DIR}/aiclaw.jar" "\$@"
EOF
chmod 755 "$BIN_DIR/aiclaw"

PROFILE="${HOME}/.profile"
mkdir -p "${HOME}" 
touch "$PROFILE"
if ! grep -Fq 'AICLAW_HOME=' "$PROFILE"; then
  cat >> "$PROFILE" <<EOF

# aiclaw
export AICLAW_HOME="${LIB_DIR}"
export PATH="${BIN_DIR}:\$PATH"
EOF
fi
export AICLAW_HOME="$LIB_DIR"
export PATH="$BIN_DIR:$PATH"

printf 'Installed aiclaw to %s\n' "$BIN_DIR/aiclaw"
printf 'Run: %s --help\n' "$BIN_DIR/aiclaw"
printf 'For the current shell: export PATH="%s:\$PATH"\n' "$BIN_DIR"
