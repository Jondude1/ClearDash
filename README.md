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

## First install
1. Download the latest `ClearDash-*.apk` from Releases and install it.
2. Open ClearDash → Setup, and follow the cards top to bottom.
3. In Android Auto's developer settings, turn on **Unknown sources** so ClearDash appears in the car.

## Credits
CX-5 photo: "2018 Mazda CX-5 2.5L, 4.5.20" by [Ghostofakina](https://commons.wikimedia.org/wiki/User:Ghostofakina),
via [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:2018_Mazda_CX-5_2.5L,_4.5.20.jpg),
licensed [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Modified: background removed,
license plate blurred, resized. The modified image (`app/src/main/res/drawable-nodpi/cx5_blue.webp`) is shared under the same license.
