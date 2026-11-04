# F-Droid repo

The phone, TV and watch apps are published in our own F-Droid repo,
<https://takeoutfm.com/fdroid/repo>. The repo lives outside git in `~/fdroid` (`config.yml`,
the repo signing key `keystore.p12`, `repo/` with every published APK); this directory holds
what's versioned: the app metadata and the publish script.

Keep `keystore.p12`: everyone who added the repo trusts its fingerprint, and a new key means
re-adding the repo. The APKs are signed with the release key from `android/key.properties`
(`8d25f6…`, the same as the Flutter apps); `publish.sh` refuses anything else.

## Setup

```
sudo apt install fdroidserver
```

`~/fdroid/config.yml` sets `serverwebroot` (where `fdroid deploy` rsyncs `repo/`).

## Releasing

1. Raise `takeout.versionCode` and `takeout.versionName` in `gradle.properties` (one version
   for all three apps; the code must always go up).
2. `fdroid/publish.sh` — builds the three release APKs, copies them into `~/fdroid/repo` as
   `<appId>-<versionName>.apk`, copies `metadata/` over and runs `fdroid update`.
3. `fdroid/publish.sh --deploy` (or `cd ~/fdroid && fdroid deploy`) uploads it.

Older versions beyond `archive_older` (config.yml) move to the repo's archive.

## Metadata

`metadata/<appId>.yml` is the
[build metadata](https://f-droid.org/docs/Build_Metadata_Reference/) (name, summary,
description, links); store graphics go in `metadata/<appId>/en-US/` (`icon.png`,
`featureGraphic.png`, `tvBanner.png`; see
[Descriptions, Graphics and Screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)).

Screenshots aren't kept here: `publish.sh` takes the website's
(`~/projects/takeoutfm_site/content/docs/apps/{android,wearos,tv}/images/screenshots`, or
`$TAKEOUTFM_SITE`) as `phoneScreenshots/`, `wearScreenshots/` and `tvScreenshots/`, so
updating them on the site updates F-Droid on the next publish.

`com.takeoutfm.mobile` and `com.takeoutfm.watch` in the repo are the old Flutter apps; the
native ones are new apps beside them (`com.takeoutfm.android`, `com.takeoutfm.wear`).
