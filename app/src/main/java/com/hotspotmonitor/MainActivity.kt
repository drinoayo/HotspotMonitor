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
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

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

        setupNotifChannel()
        setupViews()
        askPermissions()
        handler.post(ticker)
    }

    private fun setupNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("hm_ch", "Hotspot Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Alerts when devices connect or blocked devices are detected"
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

        val etIp = findViewById<EditText>(R.id.etTvIp)
        tvIp?.let { etIp.setText(it) }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            tvIp = etIp.text.toString().trim()
            prefs.edit().putString("tv_ip", tvIp).apply()
            tvTvStatus.text = "IP saved: $tvIp — tap a button to test"
            tvTvStatus.setTextColor(0xFF1A73E8.toInt())
        }

        val keys = mapOf(
            R.id.btnPower to "VK_POWER", R.id.btnVolUp to "VK_VOLUME_UP",
            R.id.btnVolDown to "VK_VOLUME_DOWN", R.id.btnMute to "VK_MUTE",
            R.id.btnChUp to "VK_CHANNEL_UP", R.id.btnChDown to "VK_CHANNEL_DOWN",
            R.id.btnUp to "VK_UP", R.id.btnDown to "VK_DOWN",
            R.id.btnLeft to "VK_LEFT", R.id.btnRight to "VK_RIGHT",
            R.id.btnOk to "VK_ENTER", R.id.btnBack to "VK_BACK",
            R.id.btnHome to "VK_HOME", R.id.btnMenu to "VK_MENU",
            R.id.btnInput to "VK_INPUT"
        )
        keys.forEach { (id, key) -> findViewById<Button>(id).setOnClickListener { sendKey(key) } }

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
        tvSession.text = if (mins < 60) "Session: ${mins}m" else "Session: ${mins/60}h ${mins%60}m"
    }

    private fun alertNewAndBlocked(devices: List<DeviceInfo>) {
        devices.forEach { d ->
            if (!knownMacs.contains(d.mac)) {
                knownMacs.add(d.mac)
                prefs.edit().putStringSet("known_macs", knownMacs).apply()
                sendNotif("New device joined hotspot", "${d.name} — ${d.ip}", d.mac.hashCode())
            }
            if (blockedMacs.contains(d.mac)) {
                sendNotif("Blocked device detected!", "${d.name} is on your hotspot. Change password to remove them.", d.mac.hashCode() + 9999)
            }
        }
    }

    private fun sendNotif(title: String, text: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(this, "hm_ch")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true).build()
        NotificationManagerCompat.from(this).notify(id, n)
    }

    private fun sendKey(key: String) {
        val ip = tvIp?.takeIf { it.isNotEmpty() } ?: run {
            Toast.makeText(this, "Enter TV IP first and tap Set IP", Toast.LENGTH_SHORT).show()
            return
        }
        tvTvStatus.text = "Sending $key..."
        tvTvStatus.setTextColor(0xFF888888.toInt())
        Thread {
            val ok = post("http://$ip:8080/sendremotekey.asp", "key=$key", "application/x-www-form-urlencoded")
                  || post("http://$ip/api/v1/keyevent", "{\"keyCode\":\"$key\"}", "application/json")
                  || post("http://$ip:1925/6/input/key", "{\"key\":\"$key\"}", "application/json")
            handler.post {
                tvTvStatus.text = if (ok) "✓ Sent: $key" else "✗ No response — verify IP and same network"
                tvTvStatus.setTextColor(if (ok) 0xFF0F9D58.toInt() else 0xFFD32F2F.toInt())
            }
        }.start()
    }

    private fun post(endpoint: String, body: String, ct: String): Boolean {
        return try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            conn.setRequestProperty("Content-Type", ct)
            conn.outputStream.write(body.toByteArray())
            val code = conn.responseCode
            conn.disconnect(); code in 200..299
        } catch (e: Exception) { false }
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
        b < 1024 -> "$b B"; b < 1048576 -> "${b/1024} KB"
        b < 1073741824 -> "${b/1048576} MB"; else -> "${b/1073741824} GB"
    }
}
