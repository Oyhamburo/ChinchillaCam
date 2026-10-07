#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ "$(uname -m)" != arm64 ]]; then
    echo 'macOS packaging requires an arm64 Apple Silicon host' >&2
    exit 1
fi

plist=packaging/macos/Info.plist
version=$(awk -F '"' '/^version = "/ { print $2; exit }' desktop/app/Cargo.toml)
plist_version=$(plutil -extract CFBundleShortVersionString raw -o - "$plist")
if [[ -z "$version" || "$version" != "$plist_version" ]]; then
    echo "Cargo version ($version) differs from Info.plist version ($plist_version)" >&2
    exit 1
fi

cargo build --release --offline --manifest-path desktop/app/Cargo.toml
app=dist/ChinchillaCam.app
zip="ChinchillaCam-${version}-macos-arm64.zip"
# Start from a clean bundle so stale files never leak into a release.
rm -rf "$app" "dist/$zip" dist/SHA256SUMS-macos.txt
mkdir -p "$app/Contents/MacOS"
cp desktop/app/target/release/chinchillacam "$app/Contents/MacOS/chinchillacam"
cp "$plist" "$app/Contents/Info.plist"
codesign --force --sign - --timestamp=none "$app"
codesign --verify --strict "$app"
ditto -c -k --keepParent "$app" "dist/$zip"
(cd dist && shasum -a 256 "$zip" > SHA256SUMS-macos.txt)
printf 'Bundle: %s\nArchive: dist/%s\nChecksum: dist/SHA256SUMS-macos.txt\n' "$app" "$zip"
