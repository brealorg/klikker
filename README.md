# Klikker

A remote for Android TV, built for Material 3 Expressive. Klikker talks to the TV over the
Android TV Remote Protocol v2, and can control a Denon/Marantz receiver's volume directly.

[Download the latest APK](https://github.com/brealorg/klikker/releases/latest)

## Features

- **Volume ring**: turn the knob to change volume, with adjustable haptics and a maximum receiver volume.
- **Denon/Marantz receivers**: found on the network and controlled directly over the network.
  The receiver only allows one control connection, so Klikker lets go of it when idle and falls
  back to the receiver's web interface while another app holds it.
- **Now playing**: poster or cover art, title and a seek bar for what is playing on the TV.
- **Apps**: shortcuts shown as app icons, learned from each TV, opened through their own links.
- **Typing**: use the phone's keyboard in TV text fields, with edits made on the TV followed back.
- **Scenes**: save how the TV is set up and run it with one tap.
- **Quick settings tiles**: TV power and mute.
- **Wake**: wakes a sleeping SHIELD with the NVIDIA button when Power is swallowed.
- **Updates**: tells you when a newer release is out and installs it from inside the app.

## Updates and network use

Klikker otherwise talks only to devices on your own network. The one exception is the update
check: when the remote opens (at most once an hour) it asks `api.github.com` for the latest
release of this repository, and shows a banner if it is newer than the installed version. Tapping
**Update** downloads the APK from GitHub, checks it against the checksum GitHub lists for it and
hands it to Android's installer, which asks you to confirm and only accepts an update signed with
the same key as the installed app. Nothing else is sent, and debug builds never check.

Turn it off under Settings → Check for updates. Android may ask you to allow Klikker to install
apps the first time you update.

## Companion

Android TV does not let a phone see what is playing, so Klikker has a small companion app for the
TV (`companion/`). It reads the TV's media sessions through notification access and serves
what is playing to the phone.

- Phones pair with the companion by opening `tvremote-companion://pair?secret=...` on the TV.
- The companion is served over HTTPS with a self-signed certificate. Before a phone sends its
  secret, the companion proves it holds the same secret (an HMAC over a nonce and the
  certificate's fingerprint), and the phone then pins the certificate.

## Building

Requires JDK 17+ and the Android SDK.

```
./gradlew assembleDebug      # debug builds of the phone app and the companion
./gradlew assembleRelease    # signed release build, see below
./gradlew test               # unit tests
```

The phone app needs Android 10 (API 29) or newer. The companion has the same requirement.

### Signing

Release builds are signed with a keystore outside the repository. Gradle reads its settings from
`~/.android/klikker-signing.properties`, or from the file named in `KLIKKER_SIGNING`:

```
storeFile=/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file, `assembleRelease` produces an unsigned APK.

## Licence

[GPL-3.0](LICENSE)
