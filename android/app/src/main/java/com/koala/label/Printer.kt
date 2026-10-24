package com.koala.label

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Polono PL80E en Bluetooth basse consommation.
 * Même réglage que la PWA validée : paquets de 180 octets sans accusé, chaque paquet
 * part dès que le téléphone a envoyé le précédent (contrôle de flux d'Android).
 * L'imprimante n'accepte qu'un appareil : elle est libérée 8 s après la dernière impression
 * ou quand l'appli passe en arrière-plan.
 */
@SuppressLint("MissingPermission")
class Printer(private val ctx: Context) {
    private val svcId = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    private val chrId = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
    private val stateId = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val prefs = ctx.getSharedPreferences("printer", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var chr: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var printing = false
    private val events = LinkedBlockingQueue<Pair<String, Int>>()
    private val states = LinkedBlockingQueue<Int>()   // octet d'état envoyé par l'imprimante (canal ff01)
    private var listening = false

    private val release = Runnable { worker.execute { if (!printing) close() } }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) events.offer("connected" to 0)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) events.offer("disconnected" to status)
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            events.offer("mtu" to status)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.offer("services" to status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.offer("write" to status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { events.offer("desc" to status) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { note(c, value) }
        @Deprecated("Android 12 et avant")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) note(c, c.value ?: ByteArray(0))
        }
    }

    /** Imprime le travail TSPL ; [done] reçoit null si tout est parti, sinon le message d'erreur. */
    fun print(job: ByteArray, done: (String?) -> Unit) {
        main.removeCallbacks(release)
        worker.execute {
            printing = true
            val err = try {
                connect()
                checkState()
                send(job)
                null
            } catch (e: Exception) {
                close()
                e.message ?: "erreur inconnue"
            } finally {
                printing = false
            }
            main.removeCallbacks(release)
            main.postDelayed(release, 8000)
            main.post { done(err) }
        }
    }

    private fun note(c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid == stateId && v.isNotEmpty()) states.offer(v[0].toInt() and 0xff)
    }

    /**
     * Demande son état à l'imprimante (ESC ! ?) avant d'imprimer.
     * Codes relevés sur la PL80E : 00 = prête, 01 = capot ouvert, 04 = plus d'étiquettes.
     * Sans réponse, on imprime quand même.
     */
    @Suppress("DEPRECATION")
    private fun checkState() {
        val g = gatt ?: return
        if (!listening) {
            val c = g.services.firstNotNullOfOrNull { it.getCharacteristic(stateId) } ?: return
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: return
            val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
                     else { d.value = v; g.writeDescriptor(d) }
            if (!ok || runCatching { await("desc", 2000) }.isFailure) return
            listening = true
        }
        states.clear()
        send(byteArrayOf(0x1b, 0x21, 0x3f))
        val st = states.poll(800, TimeUnit.MILLISECONDS) ?: return
        when {
            (st and 0x01) != 0 -> throw IllegalStateException("capot ouvert. Referme-le, puis touche à nouveau Imprimer")
            (st and 0x04) != 0 -> throw IllegalStateException("plus d'étiquettes. Remets un rouleau, puis touche à nouveau Imprimer")
            (st and 0x02) != 0 -> throw IllegalStateException("étiquette coincée. Ouvre le capot pour la dégager")
            (st and 0x10) != 0 -> throw IllegalStateException("imprimante en pause. Appuie sur son bouton, puis réessaie")
        }
    }

    /** Libère tout de suite l'imprimante (appli en arrière-plan). */
    fun releaseNow() {
        main.removeCallbacks(release)
        worker.execute { if (!printing) close() }
    }

    private fun await(type: String, ms: Long): Int {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) throw IllegalStateException("timeout")
            val e = events.poll(left, TimeUnit.MILLISECONDS) ?: throw IllegalStateException("timeout")
            if (e.first == type) return e.second
            if (e.first == "disconnected") throw IllegalStateException("disconnected")
        }
    }

    private fun connect() {
        if (gatt != null && chr != null) return
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IllegalStateException("Bluetooth indisponible sur cet appareil")
        if (!adapter.isEnabled) throw IllegalStateException("active le Bluetooth puis réessaie")
        val saved = prefs.getString("addr", null)
        if (saved != null && runCatching { open(adapter.getRemoteDevice(saved)) }.isSuccess) return
        // première fois, ou imprimante changée : on la cherche par son nom
        val dev = scan(adapter) ?: throw IllegalStateException("imprimante introuvable. Allume-la, puis touche à nouveau Imprimer")
        try { open(dev) } catch (e: Exception) {
            throw IllegalStateException("connexion à l'imprimante impossible. Éteins-la et rallume-la, puis réessaie")
        }
        prefs.edit().putString("addr", dev.address).apply()
    }

    private fun open(dev: BluetoothDevice) {
        close()
        events.clear()
        mtu = 23
        val g = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        await("connected", 7000)
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (g.requestMtu(247)) runCatching { await("mtu", 3000) }
        if (!g.discoverServices()) throw IllegalStateException("services")
        if (await("services", 8000) != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("services")
        chr = g.getService(svcId)?.getCharacteristic(chrId) ?: throw IllegalStateException("canal d'impression absent")
    }

    private fun scan(adapter: android.bluetooth.BluetoothAdapter): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = AtomicReference<BluetoothDevice?>(null)
        val lock = Object()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: return
                if (name.startsWith("PL80", ignoreCase = true) && found.compareAndSet(null, r.device)) synchronized(lock) { lock.notifyAll() }
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        try { synchronized(lock) { if (found.get() == null) lock.wait(6000) } } finally { runCatching { scanner.stopScan(cb) } }
        return found.get()
    }

    private fun send(job: ByteArray) {
        val g = gatt ?: throw IllegalStateException("non connectée")
        val c = chr ?: throw IllegalStateException("non connectée")
        val size = minOf(180, mtu - 3)
        events.clear()
        var i = 0
        try {
            while (i < job.size) {
                val part = job.copyOfRange(i, minOf(i + size, job.size))
                var tries = 0
                while (!write(g, c, part)) {           // pile Bluetooth occupée : on réessaie un peu plus tard
                    if (++tries > 400) throw IllegalStateException("busy")
                    Thread.sleep(5)
                }
                await("write", 5000)
                i += part.size
            }
        } catch (e: Exception) {
            throw IllegalStateException("envoi interrompu. Éteins puis rallume l'imprimante avant de réessayer")
        }
    }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, part: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, part, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            c.value = part
            g.writeCharacteristic(c)
        }

    private fun close() {
        chr = null
        listening = false
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }
}
