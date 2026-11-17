# ClearDash

An Android Auto dashboard for the CX-5 that pulls everything into one screen:

- **Speed** from GPS (OBD as fallback) with the **speed limit** from OpenStreetMap
- **Next turn** from Google Maps / Waze while they navigate in the background
- **Engine data** from a VePEAK OBD2 adapter (classic Bluetooth or BLE) — ClearDash finds the
  adapter itself and *verifies* it with a live ELM327 handshake (ATZ/ATI identity, supply
  voltage, engine-data probe) before trusting it; a hand-picked adapter is never overwritten
- **Tire pressure & temperature** from Bluetooth valve-cap sensors — the 4 caps are
  auto-found and *verified* during a scan (each must report a stable, plausible pressure
  several times) before being saved
- **Wheel assignment by proof-of-change**: the setup wizard asks you to let a little air
  out of one tire at a time (or unscrew/re-screw its cap) — the sensor whose reading
  changes is assigned to that wheel, so a cap is never placed on the wrong corner by a guess
- **Verify-all checklist** in Setup: permissions, turn-info listener, GPS, the VePEAK and all
  4 caps each show pass/fail from live state, with a one-tap full re-verification
- **Check-engine fault codes**: stored + pending DTCs are read about every 30 s (read-only —
  codes are never cleared), decoded to plain English, shown in Setup and raised as alerts
- **Tire rotation remap**: one tap reassigns the caps after a front↔back or cross-pattern
  rotation, or re-runs the wheel-learning wizard
- **Settings backup / restore**: export your adapter + cap assignments and preferences as
  text and re-import them after a reinstall, so a forced uninstall no longer wipes your setup
- **Alerts** for low/leaking tires, overheating and charging problems, on the car screen and the phone
- **Look**: the ATLAS "Stark" hologram — cyan light on near-black teal glass, cut-corner panels with corner
  brackets, a reactor-ring speedometer, Space Mono readouts and Sora text — laid out for the CX-5's 800×480 screen
  and driven entirely from the commander knob
- **POWER view**: a live top-down powertrain schematic — energy pulses from the engine through the
  transmission to the wheels (and the rear axle when i-Activ AWD engages), fuel, intake air, exhaust and
  charging flows, cylinders flashing in firing order, plus estimated horsepower, load, throttle and fuel burn
- **Ambient mode**: parked for 90 s, the dash eases into a slow screensaver — the hologram turning centre
  stage, breathing systems readouts, the clock and what's playing. Any knob turn wakes it
- **Idle diagnostics sweep**: stopped for 8 s (a red light, a drive-through), a scan line passes over the
  hologram once and reports "all systems nominal" — or the one thing worth knowing (running hot, charging low,
  fuel low, idle high, or an engineering note)
- **Engineering notes**: ClearDash learns what's normal for your car (fuel trims, idle drift, charging
  voltage, warm-up time, temperatures, mpg by cruising speed) and turns it into plain-English suggestions
  on the phone, in the car's ⌘ menu, and for ATLAS

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

## Parked for now
Tire-cap gauges and OBD2 warnings (engine/charging alerts, check-engine codes) are switched off in
`Features.kt`. Their code is intact; flip `TIRES` / `OBD_ALERTS` to `true` to bring them back.
Live engine readouts (RPM, coolant, battery, fuel, load, intake) stay on.

## Credits
Fonts: [Space Mono](https://github.com/googlefonts/spacemono) and [Sora](https://github.com/sora-xor/sora-font),
both under the SIL Open Font License 1.1 (see `FONT-LICENSE-SPACEMONO.txt`, `FONT-LICENSE-SORA.txt`).

CX-5 photo: "2018 Mazda CX-5 2.5L, 4.5.20" by [Ghostofakina](https://commons.wikimedia.org/wiki/User:Ghostofakina),
via [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:2018_Mazda_CX-5_2.5L,_4.5.20.jpg),
licensed [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Modified: background removed,
license plate blurred, resized. The modified image (`app/src/main/res/drawable-nodpi/cx5_blue.webp`) is shared under the same license.

Hologram proportions were measured from a side-on photo of a 2017 Mazda CX-5 by KarleHorn,
via [Wikimedia Commons](https://commons.wikimedia.org/wiki/User:KarleHorn), licensed
[CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). The photo itself is not included in the app.
