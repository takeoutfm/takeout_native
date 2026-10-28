#!/bin/sh
# Fetches VideoLAN's prebuilt VLCKit (macOS, 411 MB unpacked) and MobileVLCKit (iOS, device and
# simulator) into Vendor/ (not committed), which the Xcode project links and embeds. Both are
# LGPL 2.1; the licence is kept as Vendor/VLCKit-COPYING.txt.
set -eu
cd "$(dirname "$0")"

BASE=https://download.videolan.org/pub/cocoapods/prod

# fetch <framework> <archive> <sha256> <folder inside the archive>
fetch() {
    if [ -d "Vendor/$1.xcframework" ]; then
        echo "Vendor/$1.xcframework is already there."
        return
    fi
    mkdir -p Vendor/download
    [ -f "Vendor/download/$2" ] || curl -fL -o "Vendor/download/$2" "$BASE/$2"
    echo "$3  Vendor/download/$2" | shasum -a 256 -c -
    rm -rf Vendor/extract
    mkdir Vendor/extract
    tar -xJf "Vendor/download/$2" -C Vendor/extract "$4/$1.xcframework" "$4/COPYING.txt"
    mv "Vendor/extract/$4/$1.xcframework" Vendor/
    mv "Vendor/extract/$4/COPYING.txt" Vendor/VLCKit-COPYING.txt
    rm -rf Vendor/extract
    echo "$1 is in Vendor/."
}

fetch VLCKit VLCKit-3.7.3-319ed2c0-79128878.tar.xz \
    019afdae4e2e2d0f3ac325fac8f7ba0af25dca70b9d157df7d60db88e0be8e5d "VLCKit - binary package"
fetch MobileVLCKit MobileVLCKit-3.7.3-319ed2c0-79128878.tar.xz \
    0d04059906962ddc9a7bd1ebaa12e1f9ae85eb2466116a97a2f46886dd27a0a9 MobileVLCKit-binary
