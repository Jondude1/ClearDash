package com.jondude1.cleardash.data

/**
 * Diagnostic trouble code (DTC) parsing + plain-English descriptions.
 *
 * Wire format (ELM327, headers/spaces off): a mode 03 reply is "43" followed by
 * 2-byte DTCs; mode 07 (pending codes) is the same after "47". A 0x0000 pair is
 * padding. Each pair's first hex digit packs the system (top 2 bits:
 * 0=P powertrain, 1=C chassis, 2=B body, 3=U network) and the code's second
 * digit (bottom 2 bits); the remaining 3 hex digits follow verbatim.
 *
 * Read-only by design: nothing here (or anywhere in ClearDash) sends mode 04.
 */
object Dtcs {

    /**
     * Parses an ELM reply into DTCs, or returns null when the reply carries no
     * recognisable response header (e.g. "NO DATA") — callers keep the old list then.
     */
    fun parse(reply: String?, header: String, pending: Boolean): List<Dtc>? {
        if (reply == null) return null
        val clean = reply.uppercase().replace(Regex("[^0-9A-F]"), "")
        val i = clean.indexOf(header)
        if (i < 0) return null
        val hex = clean.substring(i + header.length)
        val out = mutableListOf<Dtc>()
        for (pair in hex.chunked(4)) {
            if (pair.length < 4) break
            val value = pair.toIntOrNull(16) ?: continue
            if (value == 0) continue // padding
            val first = pair[0].digitToInt(16)
            val system = when ((first shr 2) and 0x3) {
                0 -> 'P'; 1 -> 'C'; 2 -> 'B'; else -> 'U'
            }
            val code = "$system${first and 0x3}${pair.substring(1)}"
            if (out.none { it.code == code }) out += Dtc(code, describe(code), pending)
        }
        return out
    }

    fun describe(code: String): String =
        DESCRIPTIONS[code] ?: "Fault code — look up $code"

    private val DESCRIPTIONS = mapOf(
        "P0030" to "Oxygen sensor heater circuit fault (bank 1, sensor 1)",
        "P0031" to "Oxygen sensor heater control circuit low (bank 1, sensor 1)",
        "P0032" to "Oxygen sensor heater control circuit high (bank 1, sensor 1)",
        "P0036" to "Oxygen sensor heater circuit fault (bank 1, sensor 2)",
        "P0037" to "Oxygen sensor heater control circuit low (bank 1, sensor 2)",
        "P0038" to "Oxygen sensor heater control circuit high (bank 1, sensor 2)",
        "P0043" to "Oxygen sensor heater control circuit low (bank 1, sensor 3)",
        "P0044" to "Oxygen sensor heater control circuit high (bank 1, sensor 3)",
        "P0050" to "Oxygen sensor heater circuit fault (bank 2, sensor 1)",
        "P0051" to "Oxygen sensor heater control circuit low (bank 2, sensor 1)",
        "P0052" to "Oxygen sensor heater control circuit high (bank 2, sensor 1)",
        "P0056" to "Oxygen sensor heater circuit fault (bank 2, sensor 2)",
        "P0057" to "Oxygen sensor heater control circuit low (bank 2, sensor 2)",
        "P0058" to "Oxygen sensor heater control circuit high (bank 2, sensor 2)",
        "P0101" to "Mass air flow sensor range/performance problem",
        "P0113" to "Intake air temperature sensor reading too high",
        "P0118" to "Coolant temperature sensor reading too high",
        "P0128" to "Coolant thermostat fault — engine running too cool",
        "P0131" to "Oxygen sensor low voltage (bank 1, sensor 1)",
        "P0134" to "Oxygen sensor shows no activity (bank 1, sensor 1)",
        "P0171" to "Fuel system too lean (bank 1)",
        "P0172" to "Fuel system too rich (bank 1)",
        "P0174" to "Fuel system too lean (bank 2)",
        "P0191" to "Fuel rail pressure sensor range problem",
        "P0300" to "Random / multiple cylinder misfire",
        "P0301" to "Cylinder 1 misfire",
        "P0302" to "Cylinder 2 misfire",
        "P0303" to "Cylinder 3 misfire",
        "P0304" to "Cylinder 4 misfire",
        "P0325" to "Knock sensor circuit fault (bank 1)",
        "P0340" to "Camshaft position sensor circuit fault",
        "P0401" to "EGR flow too low",
        "P0420" to "Catalytic converter efficiency low (bank 1)",
        "P0430" to "Catalytic converter efficiency low (bank 2)",
        "P0442" to "Evaporative emissions small leak detected",
        "P0455" to "Evaporative emissions large leak — check the fuel cap",
        "P0456" to "Evaporative emissions very small leak detected",
        "P0500" to "Vehicle speed sensor fault",
        "P0562" to "System voltage low",
        "P0606" to "Engine computer (ECM/PCM) processor fault",
        "P0700" to "Transmission control system fault",
        "P2187" to "Fuel system too lean at idle (bank 1)",
        "P2189" to "Fuel system too lean at idle (bank 2)",
        "C0035" to "Front left wheel speed sensor fault",
        "U0100" to "Lost communication with the engine computer",
    )
}
