#!/bin/sh
# Builds the phone, TV and watch release APKs and adds them to the F-Droid repo (see README.md).
#
#   fdroid/publish.sh            build, copy into the repo, update its index
#   fdroid/publish.sh --deploy   ... and upload it (fdroid deploy, serverwebroot in config.yml)
#
# FDROID_DIR is the repo (config.yml, keystore.p12, repo/); default ~/fdroid.
# TAKEOUTFM_SITE is the website checkout the screenshots come from; default ~/projects/takeoutfm_site.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
repo=${FDROID_DIR:-$HOME/fdroid}
site=${TAKEOUTFM_SITE:-$HOME/projects/takeoutfm_site}
# The release key's certificate (android/key.properties); every APK must be signed with it.
signer=8d25f6b695579669411378cad10f89c22c0ddfa2dd39b3b5b5b63f85f843b169

deploy=
case "${1:-}" in
    --deploy) deploy=1 ;;
    "") ;;
    *) echo "usage: $0 [--deploy]" >&2; exit 2 ;;
esac

command -v fdroid >/dev/null || { echo "fdroid not found (apt install fdroidserver)" >&2; exit 1; }
[ -f "$repo/config.yml" ] || { echo "no F-Droid repo in $repo" >&2; exit 1; }
[ -d "$site/content/docs/apps" ] || { echo "no website checkout in $site (TAKEOUTFM_SITE)" >&2; exit 1; }

sdk=$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")
tools=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
version=$(sed -n 's/^takeout\.versionName=//p' "$root/gradle.properties")
# The build needs JDK 17 (apt's fdroidserver pulls in a newer default java).
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}

(cd "$root" && ./gradlew :android:assembleRelease :tv:assembleRelease :wear:assembleRelease)

for pair in android:com.takeoutfm.android tv:com.takeoutfm.tv wear:com.takeoutfm.wear; do
    module=${pair%%:*}
    id=${pair#*:}
    apk=$root/$module/build/outputs/apk/release/$module-release.apk
    if ! "$tools/apksigner" verify --print-certs "$apk" | grep -q "SHA-256 digest: $signer"; then
        echo "$apk is not signed with the release key (android/key.properties)" >&2
        exit 1
    fi
    dest=$repo/repo/$id-$version.apk
    if [ -e "$dest" ] && ! cmp -s "$apk" "$dest"; then
        echo "$dest already exists; bump takeout.versionCode/versionName in gradle.properties" >&2
        exit 1
    fi
    cp "$apk" "$dest"
done

mkdir -p "$repo/metadata"
cp "$root"/fdroid/metadata/*.yml "$repo/metadata/"
# Graphics are mirrored, so ones removed here go there too.
for dir in "$root"/fdroid/metadata/*/; do
    rsync -a --delete "$dir" "$repo/metadata/$(basename "$dir")/"
done
# Screenshots are the website's. fdroid update copies them into repo/ but never removes
# them, so each folder is mirrored there too.
for entry in android:com.takeoutfm.android/en-US/phoneScreenshots \
        wearos:com.takeoutfm.wear/en-US/wearScreenshots \
        tv:com.takeoutfm.tv/en-US/tvScreenshots; do
    shots=$site/content/docs/apps/${entry%%:*}/images/screenshots/
    rel=${entry#*:}
    for dest in "$repo/metadata/$rel" "$repo/repo/$rel"; do
        mkdir -p "$dest"
        rsync -a --delete --include='*.png' --exclude='*' "$shots" "$dest/"
    done
done

cd "$repo"
fdroid update
[ -z "$deploy" ] || fdroid deploy
