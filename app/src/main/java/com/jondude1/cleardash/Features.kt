package com.jondude1.cleardash

/**
 * Switches for parts of ClearDash that are parked for now. The code behind them is kept
 * intact, so turning one back on is a one-line change.
 */
object Features {
    /** Bluetooth tire-cap gauges: scanning, the Tires view, tire setup and tire alerts. */
    const val TIRES = false

    /** OBD2 warnings: engine/charging/battery alerts and check-engine fault codes. Live gauges stay on. */
    const val OBD_ALERTS = false
}
