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
