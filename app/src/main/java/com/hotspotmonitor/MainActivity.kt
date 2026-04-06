package com.hotspotmonitor

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress

data class DeviceInfo(val ip: String, val mac: String, val name: String)

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {
    private val list = mutableListOf<DeviceInfo>()
    fun update(d: List<DeviceInfo>) { list.clear(); list.addAll(d); notifyDataSetChanged() }
    override fun getItemCount() = list.size
    override fun onCreateViewHolder(p: ViewGroup, v: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.device_item, p, false))
    override fun onBindViewHolder(h: VH, i: Int) {
        h.name.text = if (list[i].name != list[i].ip) list[i].name else "Unknown Device"
        h.ip.text = "IP: ${list[i].ip}"
        h.mac.text = "MAC: ${list[i].mac}"
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val ip: TextView = v.findViewById(R.id.tvIp)
        val mac: TextView = v.findViewById(R.id.tvMac)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvData: TextView
    private lateinit var tvSession: TextView
    private lateinit var adapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var startBytes = 0L
    private var startTime = 0L

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

        tvStatus = findViewById(R.id.tvStatus)
        tvCount = findViewById(R.id.tvCount)
        tvData = findViewById(R.id.tvData)
        tvSession = findViewById(R.id.tvSession)

        adapter = DeviceAdapter()
        findViewById<RecyclerView>(R.id.rvDevices).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener { resetCounter() }

        val hotspot = findViewById<View>(R.id.hotspotScreen)
        val tv = findViewById<View>(R.id.tvScreen)
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_hotspot -> { hotspot.visibility = View.VISIBLE; tv.visibility = View.GONE; true }
                R.id.nav_tv -> { hotspot.visibility = View.GONE; tv.visibility = View.VISIBLE; true }
                else -> false
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)

        handler.post(ticker)
    }

    private fun resetCounter() {
        startBytes = TrafficStats.getMobileTxBytes() + TrafficStats.getMobileRxBytes()
        startTime = System.currentTimeMillis()
        prefs.edit().putLong("start_bytes", startBytes).putLong("start_time", startTime).apply()
    }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(ticker) }

    private fun refresh() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val on = try {
            val m = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            m.isAccessible = true; m.invoke(wm) as Boolean
        } catch (e: Exception) { false }

        tvStatus.text = if (on) "● Hotspot is ACTIVE" else "● Hotspot is OFF"
        tvStatus.setTextColor(if (on) 0xFF0F9D58.toInt() else 0xFFDB4437.toInt())

        val devices = arpTable()
        tvCount.text = "${devices.size}"
        adapter.update(devices)

        val used = (TrafficStats.getMobileTxBytes() + TrafficStats.getMobileRxBytes()) - startBytes
        tvData.text = fmt(if (used < 0) 0L else used)

        val mins = ((System.currentTimeMillis() - startTime) / 60000).toInt()
        tvSession.text = if (mins < 60) "Session: ${mins}m" else "Session: ${mins/60}h ${mins%60}m"
    }

    private fun arpTable(): List<DeviceInfo> {
        val r = mutableListOf<DeviceInfo>()
        try {
            val br = BufferedReader(FileReader("/proc/net/arp"))
            br.readLine()
            var l: String?
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
        b < 1024 -> "$b B"
        b < 1048576 -> "${b/1024} KB"
        b < 1073741824 -> "${b/1048576} MB"
        else -> "${b/1073741824} GB"
    }
}
