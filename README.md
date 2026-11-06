# ClearDash

An Android Auto dashboard for the CX-5 that pulls everything into one screen:

- **Speed** from GPS (OBD as fallback) with the **speed limit** from OpenStreetMap
- **Next turn** from Google Maps / Waze while they navigate in the background
- **Engine data** from a Veepeak OBD2 adapter (classic Bluetooth or BLE)
- **Tire pressure & temperature** from Bluetooth valve-cap sensors
- **Home Assistant** controls over Tailscale, popping up automatically when you get home
- **Alerts** for low/leaking tires, overheating and charging problems, on the car screen and the phone

Built by GitHub Actions on every push to `main` and published as a GitHub Release.
The app checks for new releases and updates itself.

## First install — getting it to appear in Android Auto

ClearDash is built with the Android for Cars App Library. Google only lets a
real head unit run Car App Library apps that were **installed from a trusted
source** (Google Play). The **Unknown sources** toggle in Android Auto's
developer settings does *not* cover Car App Library apps — a plain sideloaded
APK will install on the phone but never appear in the car, no matter how the
manifest is written.

Two install routes that work:

1. **KingInstaller (free, no PC):** install KingInstaller on the phone, use it
   to install the `ClearDash-*.apk` from Releases — it records Google Play as
   the installer, which is what Android Auto checks. Then open Android Auto →
   Settings → **Customize launcher** and make sure ClearDash is ticked.
2. **Play internal/closed testing (most reliable):** upload the release APK to
   a test track in Play Console and install it from the Play Store test link.
   Updates then arrive through Play automatically.

Either way, also enable **Unknown sources** (Android Auto settings → tap
**Version** 10 times → ⋮ → Developer settings) — it doesn't unlock Car App
Library apps by itself, but it stops AA hiding other sideloaded pieces.

After installing: open ClearDash on the phone → Setup, and follow the cards
top to bottom (location, Bluetooth, notification access for turn info).

> **Note (Nov 2026):** the release signing key was rotated — the original key
> had been committed to this public repo. The first build signed with the new
> key can't update the old install in place: uninstall ClearDash once, then
> install the new build. Signing material now lives in GitHub Secrets
> (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); the
> owner's backup is outside this repo.

## Credits
CX-5 photo: "2018 Mazda CX-5 2.5L, 4.5.20" by [Ghostofakina](https://commons.wikimedia.org/wiki/User:Ghostofakina),
via [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:2018_Mazda_CX-5_2.5L,_4.5.20.jpg),
licensed [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Modified: background removed,
license plate blurred, resized. The modified image (`app/src/main/res/drawable-nodpi/cx5_blue.webp`) is shared under the same license.
