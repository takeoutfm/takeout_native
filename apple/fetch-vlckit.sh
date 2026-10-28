#!/bin/sh
# Fetches VideoLAN's prebuilt VLCKit for macOS into Vendor/ (not committed: 411 MB), which the
# Xcode project links and embeds. VLCKit is LGPL 2.1; its licence is kept as
# Vendor/VLCKit-COPYING.txt.
set -eu
cd "$(dirname "$0")"

VERSION=3.7.3
ARCHIVE=VLCKit-3.7.3-319ed2c0-79128878.tar.xz
SHA256=019afdae4e2e2d0f3ac325fac8f7ba0af25dca70b9d157df7d60db88e0be8e5d

if [ -d Vendor/VLCKit.xcframework ]; then
    echo "Vendor/VLCKit.xcframework is already there."
    exit 0
fi

mkdir -p Vendor/download
curl -fL -o "Vendor/download/VLCKit-$VERSION.tar.xz" "https://download.videolan.org/pub/cocoapods/prod/$ARCHIVE"
echo "$SHA256  Vendor/download/VLCKit-$VERSION.tar.xz" | shasum -a 256 -c -

rm -rf Vendor/extract
mkdir Vendor/extract
tar -xJf "Vendor/download/VLCKit-$VERSION.tar.xz" -C Vendor/extract \
    "VLCKit - binary package/VLCKit.xcframework" "VLCKit - binary package/COPYING.txt"
mv "Vendor/extract/VLCKit - binary package/VLCKit.xcframework" Vendor/
mv "Vendor/extract/VLCKit - binary package/COPYING.txt" Vendor/VLCKit-COPYING.txt
rm -rf Vendor/extract
echo "VLCKit $VERSION is in Vendor/."
