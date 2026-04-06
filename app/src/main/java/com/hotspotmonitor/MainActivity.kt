package com.hotspotmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress

data class DeviceInfo(val ip: String, val mac: String, val name: String)

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {
    private val list = mutableListOf<DeviceInfo>()
    fun update(devices: List<DeviceInfo>) { list.clear(); list.addAll(devices); notifyDataSetChanged() }
    override fun getItemCount() = list.size
    override fun onCreateViewHolder(p: ViewGroup, v: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.device_item, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val d = list[pos]
        h.name.text = if (d.name != d.ip) d.name else "Unknown Device"
        h.ip.text = "IP: ${d.ip}"
        h.mac.text = "MAC: ${d.mac}"
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val ip: TextView = v.findViewById(R.id.tvIp)
        val mac: TextView = v.findViewById(R.id.tvMac)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvData: TextView
    private lateinit var tvSession: TextView
    private lateinit var adapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var startBytes = 0L

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 3000) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        tvCount = findViewById(R.id.tvCount)
        tvData = findViewById(R.id.tvData)
        tvSession = findViewById(R.id.tvSession)
        adapter = DeviceAdapter()
        val rv = findViewById<RecyclerView>(R.id.rvDevices)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        startBytes = TrafficStats.getMobileTxBytes() + TrafficStats.getMobileRxBytes()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
        handler.post(ticker)
    }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(ticker) }

    private fun refresh() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val hotspotOn = try {
            val m = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            m.isAccessible = true; m.invoke(wm) as Boolean
        } catch (e: Exception) { false }

        if (hotspotOn) {
            tvStatus.text = "Hotspot is ACTIVE"
            tvStatus.setTextColor(0xFF0F9D58.toInt())
        } else {
            tvStatus.text = "Hotspot is OFF"
            tvStatus.setTextColor(0xFFDB4437.toInt())
        }

        val devices = readArpTable()
        tvCount.text = "${devices.size}"
        adapter.update(devices)

        val used = (TrafficStats.getMobileTxBytes() + TrafficStats.getMobileRxBytes()) - startBytes
        tvData.text = formatBytes(used)

        val mins = (System.currentTimeMillis() - startBytes) / 60000
        tvSession.text = "Session started when app opened"
    }

    private fun readArpTable(): List<DeviceInfo> {
        val result = mutableListOf<DeviceInfo>()
        try {
            val br = BufferedReader(FileReader("/proc/net/arp"))
            br.readLine()
            var line: String?
            while (br.readLine().also { line = it } != null) {
                val parts = line!!.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val ip = parts[0]; val mac = parts[3]
                    if (mac != "00:00:00:00:00:00" && !mac.startsWith("00:00")) {
                        val hostname = try { InetAddress.getByName(ip).hostName } catch (e: Exception) { ip }
                        result.add(DeviceInfo(ip, mac, hostname))
                    }
                }
            }
            br.close()
        } catch (_: Exception) {}
        return result
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1048576 -> "${b/1024} KB"
        b < 1073741824 -> "${b/1048576} MB"
        else -> "${b/1073741824} GB"
    }
}
