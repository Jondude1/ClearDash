package com.jondude1.cleardash.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** An ELM327-style adapter link: send a command, read text up to the '>' prompt. */
interface ElmLink {
    suspend fun connect()
    suspend fun send(cmd: String, timeoutMs: Long = 2_500): String
    fun close()
}

/** Classic Bluetooth (SPP) adapters, e.g. Veepeak Mini Bluetooth. */
@SuppressLint("MissingPermission")
class SppLink(private val device: BluetoothDevice) : ElmLink {
    private var socket: BluetoothSocket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val s = device.createRfcommSocketToServiceRecord(SPP)
        socket = s
        s.connect()
    }

    override suspend fun send(cmd: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("not connected")
        val input = s.inputStream
        while (input.available() > 0) input.read() // drop stale bytes
        s.outputStream.write("$cmd\r".toByteArray())
        s.outputStream.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("closed")
                if (c.toChar() == '>') break
                sb.append(c.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        sb.toString()
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/** Bluetooth Low Energy adapters, e.g. Veepeak OBDCheck BLE / BLE+. */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context, private val device: BluetoothDevice) : ElmLink {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val ready = CompletableDeferred<Unit>()

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!ready.isCompleted) ready.completeExceptionally(IOException("disconnected"))
                incoming.trySend("\u0000DISCONNECTED")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            val notifiable = BluetoothGattCharacteristic.PROPERTY_NOTIFY
            // Prefer the well-known FFF0 / FFE0 serial services, then any vendor service.
            val ordered = g.services.sortedBy {
                val u = it.uuid.toString()
                when {
                    u.startsWith("0000fff0") -> 0
                    u.startsWith("0000ffe0") -> 1
                    u.startsWith("000018") -> 9 // standard GATT services
                    else -> 5
                }
            }
            var notify: BluetoothGattCharacteristic? = null
            var write: BluetoothGattCharacteristic? = null
            for (svc in ordered) {
                val n = svc.characteristics.firstOrNull { (it.properties and notifiable) != 0 }
                val w = svc.characteristics.firstOrNull { (it.properties and writable) != 0 }
                if (n != null && w != null) {
                    notify = n; write = w; break
                }
            }
            val n = notify
            if (n == null || write == null) {
                ready.completeExceptionally(IOException("Adapter has no serial service"))
                return
            }
            writeChar = write
            g.setCharacteristicNotification(n, true)
            val cccd = n.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } else {
                ready.complete(Unit)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (!ready.isCompleted) ready.complete(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            ch.value?.let { incoming.trySend(String(it)) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(String(value))
        }
    }

    override suspend fun connect() {
        gatt = withContext(Dispatchers.Main) {
            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        }
        withTimeout(15_000) { ready.await() }
    }

    @Suppress("DEPRECATION")
    override suspend fun send(cmd: String, timeoutMs: Long): String {
        val g = gatt ?: throw IOException("not connected")
        val ch = writeChar ?: throw IOException("not connected")
        while (incoming.tryReceive().isSuccess) { /* drop stale */ }
        ch.value = "$cmd\r".toByteArray()
        g.writeCharacteristic(ch)
        val sb = StringBuilder()
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val part = incoming.receive()
                if (part.startsWith("\u0000DISCONNECTED")) throw IOException("disconnected")
                val idx = part.indexOf('>')
                if (idx >= 0) {
                    sb.append(part.substring(0, idx)); break
                }
                sb.append(part)
            }
        }
        return sb.toString()
    }

    override fun close() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }
}

/** Polls the Veepeak for live engine data and writes it to the hub. */
class ObdSource(private val context: Context) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun status(s: String, connected: Boolean = false) =
        DataHub.update { it.copy(obd = it.obd.copy(status = s, connected = connected)) }

    @SuppressLint("MissingPermission")
    suspend fun run() {
        while (true) {
            val addr = Prefs.obdAddress
            if (addr == null) {
                status("Not set up")
                delay(5_000); continue
            }
            val bt = adapter
            if (bt == null || !bt.isEnabled) {
                status("Bluetooth is off")
                delay(5_000); continue
            }
            val device = try { bt.getRemoteDevice(addr) } catch (e: Exception) { null }
            if (device == null) {
                status("Adapter not found"); delay(10_000); continue
            }
            val link: ElmLink = if (Prefs.obdBle) BleLink(context, device) else SppLink(device)
            try {
                status("Connecting…")
                link.connect()
                initElm(link)
                status("Connected", true)
                poll(link, addr)
            } catch (e: SecurityException) {
                status("Bluetooth permission needed")
            } catch (e: Exception) {
                Log.d("OBD", "link error: ${e.message}")
                status("Waiting for adapter (car off?)")
            } finally {
                link.close()
                DataHub.update { it.copy(obd = ObdData(status = it.obd.status)) }
            }
            delay(8_000)
        }
    }

    private suspend fun initElm(link: ElmLink) {
        link.send("ATZ", 4_000)
        delay(500)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) link.send(c)
        // first query triggers protocol search, which can take a few seconds
        link.send("0100", 10_000)
    }

    private suspend fun poll(link: ElmLink, addr: String) {
        var tick = 0
        var failures = 0
        while (true) {
            if (Prefs.obdAddress != addr) return // adapter changed in settings
            val fast = mutableMapOf<String, String>()
            fast["0C"] = link.send("010C")
            fast["0D"] = link.send("010D")
            var o = DataHub.state.value.obd
            val rpm = pid(fast["0C"], "0C")?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 4 }
            val spdKmh = pid(fast["0D"], "0D")?.get(0)
            if (rpm == null && spdKmh == null) failures++ else failures = 0
            if (failures >= 6) throw IOException("no response")
            o = o.copy(rpm = rpm ?: o.rpm, speedMph = spdKmh?.let { Math.round(it * 0.621371f) } ?: o.speedMph)

            if (tick % 5 == 0) {
                o = o.copy(
                    coolantF = pid(link.send("0105"), "05")?.let { cToF(it[0] - 40f) } ?: o.coolantF,
                    load = pid(link.send("0104"), "04")?.let { it[0] * 100 / 255 } ?: o.load,
                    intakeF = pid(link.send("010F"), "0F")?.let { cToF(it[0] - 40f) } ?: o.intakeF,
                    voltage = Regex("""(\d{1,2}\.\d)""").find(link.send("ATRV"))?.value?.toFloatOrNull() ?: o.voltage,
                )
            }
            if (tick % 15 == 0) {
                o = o.copy(
                    fuelPct = pid(link.send("012F"), "2F")?.let { it[0] * 100 / 255 } ?: o.fuelPct,
                    stft = pid(link.send("0106"), "06")?.let { (it[0] - 128) * 100f / 128f } ?: o.stft,
                    ltft = pid(link.send("0107"), "07")?.let { (it[0] - 128) * 100f / 128f } ?: o.ltft,
                )
            }
            DataHub.update { it.copy(obd = o.copy(connected = true, status = "Connected")) }
            tick++
            delay(150)
        }
    }

    private fun cToF(c: Float) = c * 9f / 5f + 32f

    /** Finds "41<pid>" in an ELM reply and returns the data bytes after it. */
    private fun pid(reply: String?, pid: String): IntArray? {
        if (reply == null) return null
        val clean = reply.uppercase().replace(Regex("[^0-9A-F\n]"), "")
        for (line in clean.split('\n')) {
            val i = line.indexOf("41$pid")
            if (i < 0) continue
            val hex = line.substring(i + 4)
            val bytes = hex.chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
            if (bytes.isNotEmpty()) return bytes.toIntArray()
        }
        return null
    }
}
