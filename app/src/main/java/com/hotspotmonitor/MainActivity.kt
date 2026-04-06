package com.hotspotmonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress

data class DeviceInfo(val ip: String, val mac: String, val name: String)

class DeviceAdapter(
    private val getBlocked: () -> Set<String>,
    private val onToggle: (DeviceInfo) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {
    private val list = mutableListOf<DeviceInfo>()
    fun update(d: List<DeviceInfo>) { list.clear(); list.addAll(d); notifyDataSetChanged() }
    override fun getItemCount() = list.size
    override fun onCreateViewHolder(p: ViewGroup, v: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.device_item, p, false))
    override fun onBindViewHolder(h: VH, i: Int) {
        val d = list[i]
        val blocked = getBlocked().contains(d.mac)
        h.name.text = if (d.name != d.ip) d.name else "Unknown Device"
        h.ip.text = "IP: ${d.ip}"
        h.mac.text = "MAC: ${d.mac}"
        h.btnBlock.text = if (blocked) "Unblock" else "Block"
        h.btnBlock.setBackgroundColor(if (blocked) 0xFF388E3C.toInt() else 0xFFD32F2F.toInt())
        h.itemView.setBackgroundColor(if (blocked) 0xFFFFEBEE.toInt() else 0xFFFFFFFF.toInt())
        h.btnBlock.setOnClickListener { onToggle(d) }
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val ip: TextView = v.findViewById(R.id.tvIp)
        val mac: TextView = v.findViewById(R.id.tvMac)
        val btnBlock: Button = v.findViewById(R.id.btnBlock)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvData: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvTvStatus: TextView
    private lateinit var adapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var startBytes = 0L
    private var startTime = 0L
    private var blockedMacs = mutableSetOf<String>()
    private var knownMacs = mutableSetOf<String>()
    private var tvIp: String? = null
    private var vidaa: VidaaTv? = null

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 3000) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("hm_prefs", Context.MODE_PRIVATE)
        startBytes = prefs.getLong("start_bytes", -1L)
        startTime = prefs.getLong("start_time", -1L)
        if (startBytes == -1L) resetCounter()
        blockedMacs = prefs.getStringSet("blocked_macs", mutableSetOf())!!.toMutableSet()
        knownMacs = prefs.getStringSet("known_macs", mutableSetOf())!!.toMutableSet()
        tvIp = prefs.getString("tv_ip", null)
        tvIp?.let { vidaa = VidaaTv(it) }

        setupNotifChannel()
        setupViews()
        askPermissions()
        handler.post(ticker)
    }

    private fun setupNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("hm_ch", "Hotspot Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Device connection alerts"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun setupViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvCount = findViewById(R.id.tvCount)
        tvData = findViewById(R.id.tvData)
        tvSession = findViewById(R.id.tvSession)
        tvTvStatus = findViewById(R.id.tvTvStatus)

        adapter = DeviceAdapter({ blockedMacs }, ::toggleBlock)
        findViewById<RecyclerView>(R.id.rvDevices).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetCounter() }

        // TV IP + Pairing
        val etIp = findViewById<EditText>(R.id.etTvIp)
        val etPin = findViewById<EditText>(R.id.etPin)
        tvIp?.let { etIp.setText(it) }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val ip = etIp.text.toString().trim()
            if (ip.isEmpty()) { Toast.makeText(this, "Enter TV IP first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            tvIp = ip
            vidaa = VidaaTv(ip)
            prefs.edit().putString("tv_ip", ip).apply()
            tvTvStatus.text = "Connecting to $ip — a PIN should appear on your TV..."
            tvTvStatus.setTextColor(0xFF1A73E8.toInt())
            // Try a connection — TV shows PIN prompt if not yet paired
            Thread {
                val ok = vidaa!!.sendKey("KEY_NULL")
                handler.post {
                    tvTvStatus.text = if (ok) "✓ Connected! No PIN needed — remote is ready."
                    else "TV found. If a PIN appeared on screen, enter it below and tap Send PIN."
                    tvTvStatus.setTextColor(if (ok) 0xFF0F9D58.toInt() else 0xFF1A73E8.toInt())
                }
            }.start()
        }

        findViewById<Button>(R.id.btnSendPin).setOnClickListener {
            val pin = etPin.text.toString().trim()
            if (pin.length != 4) { Toast.makeText(this, "PIN must be 4 digits", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val v = vidaa ?: run { Toast.makeText(this, "Set TV IP first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            tvTvStatus.text = "Sending PIN..."
            tvTvStatus.setTextColor(0xFF888888.toInt())
            Thread {
                val ok = v.sendAuth(pin)
                handler.post {
                    tvTvStatus.text = if (ok) "✓ Paired! Remote buttons now work." else "✗ PIN failed — check and try again"
                    tvTvStatus.setTextColor(if (ok) 0xFF0F9D58.toInt() else 0xFFD32F2F.toInt())
                }
            }.start()
        }

        // Remote key buttons
        val keys = mapOf(
            R.id.btnPower to "KEY_POWER", R.id.btnVolUp to "KEY_VOLUMEUP",
            R.id.btnVolDown to "KEY_VOLUMEDOWN", R.id.btnMute to "KEY_MUTE",
            R.id.btnChUp to "KEY_CHANNELUP", R.id.btnChDown to "KEY_CHANNELDOWN",
            R.id.btnUp to "KEY_UP", R.id.btnDown to "KEY_DOWN",
            R.id.btnLeft to "KEY_LEFT", R.id.btnRight to "KEY_RIGHT",
            R.id.btnOk to "KEY_OK", R.id.btnBack to "KEY_BACK",
            R.id.btnHome to "KEY_HOME", R.id.btnMenu to "KEY_MENU",
            R.id.btnInput to "KEY_SOURCES"
        )
        keys.forEach { (id, key) ->
            findViewById<Button>(id).setOnClickListener { sendTvKey(key) }
        }

        val hotspot = findViewById<View>(R.id.hotspotScreen)
        val tv = findViewById<View>(R.id.tvScreen)
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_hotspot -> { hotspot.visibility = View.VISIBLE; tv.visibility = View.GONE; true }
                R.id.nav_tv -> { hotspot.visibility = View.GONE; tv.visibility = View.VISIBLE; true }
                else -> false
            }
        }
    }

    private fun sendTvKey(key: String) {
        val v = vidaa ?: run {
            Toast.makeText(this, "Enter TV IP first and tap Pair TV", Toast.LENGTH_SHORT).show(); return
        }
        tvTvStatus.text = "Sending $key..."
        tvTvStatus.setTextColor(0xFF888888.toInt())
        Thread {
            val ok = v.sendKey(key)
            handler.post {
                tvTvStatus.text = if (ok) "✓ $key sent" else "✗ No response — is TV on the hotspot?"
                tvTvStatus.setTextColor(if (ok) 0xFF0F9D58.toInt() else 0xFFD32F2F.toInt())
            }
        }.start()
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    private fun toggleBlock(d: DeviceInfo) {
        if (blockedMacs.contains(d.mac)) {
            blockedMacs.remove(d.mac)
            Toast.makeText(this, "${d.name} unblocked", Toast.LENGTH_SHORT).show()
        } else {
            blockedMacs.add(d.mac)
            Toast.makeText(this, "Blocked! Change hotspot password to disconnect ${d.name}.", Toast.LENGTH_LONG).show()
        }
        prefs.edit().putStringSet("blocked_macs", blockedMacs).apply()
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(ticker) }

    private fun resetCounter() {
        startBytes = TrafficStats.getMobileTxBytes() + TrafficStats.getMobileRxBytes()
        startTime = System.currentTimeMillis()
        prefs.edit().putLong("start_bytes", startBytes).putLong("start_time", startTime).apply()
    }

    private fun refresh() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val on = try {
            val m = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            m.isAccessible = true; m.invoke(wm) as Boolean
        } catch (e: Exception) { false }
        tvStatus.text = if (on) "● Hotspot is ACTIVE" else "● Hotspot is OFF"
        tvStatus.setTextColor(if (on) 0xFF0F9D58.toInt() else 0xFFDB4437.toInt())
        val devices = arpTable()
        alertNewAndBlocked(devices)
        tvCount.text = "${devices.size}"
        adapter.update(devices)
        val used = (TrafficStats.getMobileTxBytes() + TrafficStats.getMobileRxBytes()) - startBytes
        tvData.text = fmt(if (used < 0) 0L else used)
        val mins = ((System.currentTimeMillis() - startTime) / 60000).toInt()
        tvSession.text = if (mins < 60) "Session: ${mins}m" else "Session: ${mins / 60}h ${mins % 60}m"
    }

    private fun alertNewAndBlocked(devices: List<DeviceInfo>) {
        devices.forEach { d ->
            if (!knownMacs.contains(d.mac)) {
                knownMacs.add(d.mac)
                prefs.edit().putStringSet("known_macs", knownMacs).apply()
                sendNotif("New device joined hotspot", "${d.name} — ${d.ip}", d.mac.hashCode())
            }
            if (blockedMacs.contains(d.mac))
                sendNotif("Blocked device detected!", "${d.name} is on your hotspot. Change password to remove.", d.mac.hashCode() + 9999)
        }
    }

    private fun sendNotif(title: String, text: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(this).notify(id,
            NotificationCompat.Builder(this, "hm_ch")
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
                .setContentText(text).setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true).build())
    }

    private fun arpTable(): List<DeviceInfo> {
        val r = mutableListOf<DeviceInfo>()
        try {
            val br = BufferedReader(FileReader("/proc/net/arp"))
            br.readLine(); var l: String?
            while (br.readLine().also { l = it } != null) {
                val p = l!!.trim().split("\\s+".toRegex())
                if (p.size >= 4 && p[3] != "00:00:00:00:00:00" && !p[3].startsWith("00:00"))
                    r.add(DeviceInfo(p[0], p[3], try { InetAddress.getByName(p[0]).hostName } catch (e: Exception) { p[0] }))
            }
            br.close()
        } catch (_: Exception) {}
        return r
    }

    private fun fmt(b: Long) = when {
        b < 1024 -> "$b B"; b < 1048576 -> "${b / 1024} KB"
        b < 1073741824 -> "${b / 1048576} MB"; else -> "${b / 1073741824} GB"
    }
}
