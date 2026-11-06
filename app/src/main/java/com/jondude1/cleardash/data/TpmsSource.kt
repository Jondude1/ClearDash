package com.jondude1.cleardash.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One Bluetooth device seen during a tire-sensor scan. */
data class SeenSensor(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long,
    val decoded: TireReading?,
    val rawHex: String,
    val serviceUuids: List<String> = emptyList(),
    val seenCount: Int = 1,
    val firstSeen: Long = lastSeen,
    val avgRssi: Float = rssi.toFloat(),
)

/** Shared registry of what the tire scanner has heard, plus a raw log for decoding new brands. */
object TpmsRegistry {
    private val _seen = MutableStateFlow<Map<String, SeenSensor>>(emptyMap())
    val seen: StateFlow<Map<String, SeenSensor>> = _seen
    private val logs = HashMap<String, ArrayDeque<String>>()
    private val history = HashMap<String, ArrayDeque<TireReading>>()

    /** Every decoded reading heard from one address, oldest first — the auto-selector verifies against this. */
    fun readingsFor(address: String): List<TireReading> = synchronized(history) {
        history[address]?.toList() ?: emptyList()
    }

    fun record(address: String, name: String?, rssi: Int, record: ScanRecord?) {
        val raw = record?.bytes?.let { hex(trimAdv(it)) } ?: ""
        val decoded = record?.let { TpmsDecoder.decode(address, it) }
        val now = System.currentTimeMillis()
        if (decoded != null) {
            synchronized(history) {
                val q = history.getOrPut(address) { ArrayDeque() }
                q.addLast(decoded)
                while (q.size > 60) q.removeFirst()
            }
        }
        _seen.update { m ->
            val prev = m[address]
            val count = (prev?.seenCount ?: 0) + 1
            val avg = if (prev == null) rssi.toFloat() else (prev.avgRssi * prev.seenCount + rssi) / count
            m + (address to SeenSensor(
                address = address,
                name = name ?: record?.deviceName ?: prev?.name,
                rssi = rssi,
                lastSeen = now,
                decoded = decoded ?: prev?.decoded,
                rawHex = raw,
                serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: prev?.serviceUuids ?: emptyList(),
                seenCount = count,
                firstSeen = prev?.firstSeen ?: now,
                avgRssi = avg,
            ))
        }
        synchronized(logs) {
            val q = logs.getOrPut(address) { ArrayDeque() }
            q.addLast("${System.currentTimeMillis()} rssi=$rssi name=${name ?: record?.deviceName ?: "-"} adv=$raw")
            while (q.size > 400) q.removeFirst()
        }
        if (decoded != null) {
            val wheel = Prefs.wheelFor(address)
            if (wheel != null) DataHub.update { it.copy(tires = it.tires + (wheel to decoded)) }
        }
    }

    fun logFor(address: String): String = synchronized(logs) {
        logs[address]?.joinToString("\n") ?: ""
    }

    fun clear() {
        _seen.value = emptyMap()
        synchronized(history) { history.clear() }
    }

    private fun trimAdv(b: ByteArray): ByteArray {
        // ScanRecord.bytes is zero-padded to 62 bytes; cut the padding.
        var end = b.size
        while (end > 0 && b[end - 1] == 0.toByte()) end--
        return b.copyOf(end)
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
}

/**
 * Decoders for Bluetooth tire caps whose broadcasts are known.
 * Unknown brands still show up in the scanner with their raw data so a decoder can be added.
 */
object TpmsDecoder {
    /** Manufacturer ID 0x0100, 16 data bytes: address(6) pressure Pa(u32 LE) temp centi-°C(s32 LE) battery% alarm. */
    private const val GENERIC_MFG = 0x0100

    fun decode(address: String, r: ScanRecord): TireReading? {
        r.getManufacturerSpecificData(GENERIC_MFG)?.let { d ->
            if (d.size >= 16) {
                val pa = le32(d, 6)
                val tempC = le32(d, 10).toInt() / 100f
                val psi = pa / 1000f * 0.145038f
                if (psi in 0f..150f && tempC in -40f..130f) {
                    // ZEEPIN-style sets encode the cap number in the first data byte:
                    // 0x80..0x83 = caps 1..4; some clones use a plain 1..4.
                    val b0 = d[0].toInt() and 0xFF
                    val sensorIndex = when {
                        b0 in 0x80..0x83 -> b0 - 0x7F
                        b0 in 1..4 -> b0
                        else -> null
                    }
                    return TireReading(
                        id = address,
                        psi = psi,
                        tempF = tempC * 9f / 5f + 32f,
                        battery = (d[14].toInt() and 0xFF).takeIf { it in 0..100 },
                        alarm = d[15].toInt() != 0,
                        seenAt = System.currentTimeMillis(),
                        sensorIndex = sensorIndex,
                    )
                }
            }
        }
        return null
    }

    private fun le32(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF) or ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or ((d[o + 3].toLong() and 0xFF) shl 24)

    val manufacturerIds = listOf(GENERIC_MFG)
}

/** Listens for tire caps. Background mode uses filters so it keeps working with the screen off. */
@SuppressLint("MissingPermission")
class TpmsSource(context: Context) {
    private val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            TpmsRegistry.record(result.device.address, result.scanRecord?.deviceName, result.rssi, result.scanRecord)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("TPMS", "scan failed $errorCode")
            DataHub.update { it.copy(tpmsScanning = false) }
        }
    }

    fun startBackground() {
        val filters = mutableListOf<ScanFilter>()
        TpmsDecoder.manufacturerIds.forEach {
            filters += ScanFilter.Builder().setManufacturerData(it, ByteArray(0)).build()
        }
        Wheel.entries.mapNotNull { Prefs.tireId(it) }.distinct().forEach {
            filters += ScanFilter.Builder().setDeviceAddress(it).build()
        }
        start(filters, ScanSettings.SCAN_MODE_BALANCED)
    }

    fun stopBackground() = stop()

    /** Unfiltered scan for the setup screen — hears every nearby device. */
    fun startDiscovery() = start(emptyList(), ScanSettings.SCAN_MODE_LOW_LATENCY)

    fun stopDiscovery() = stop()

    private fun start(filters: List<ScanFilter>, mode: Int) {
        try {
            val settings = ScanSettings.Builder().setScanMode(mode).build()
            scanner?.startScan(filters, settings, cb)
            DataHub.update { it.copy(tpmsScanning = scanner != null) }
        } catch (e: Exception) {
            Log.w("TPMS", "can't scan: ${e.message}")
        }
    }

    private fun stop() {
        try { scanner?.stopScan(cb) } catch (_: Exception) {}
    }
}
