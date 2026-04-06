package com.hotspotmonitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

class VidaaTv(private val host: String, private val context: Context) {

    companion object {
        private const val PORT = 36669
        private const val CLIENT_ID = "HotspotMonitor"
        private const val USERNAME = "hisenseservice"
        private const val PASSWORD = "multimqttservice"
    }

    fun sendKey(key: String): Boolean {
        val topic = "/remoteapp/tv/remote_service/$CLIENT_ID/actions/sendkey"
        return send(topic, key)
    }

    fun sendAuth(pin: String): Boolean {
        val topic = "/remoteapp/tv/ui_service/$CLIENT_ID/actions/authenticationcode"
        return send(topic, "{\"authNum\":$pin}")
    }

    private fun send(topic: String, payload: String): Boolean {
        return tryBound(false, topic, payload)
            || tryBound(true, topic, payload)
            || tryDirect(false, topic, payload)
            || tryDirect(true, topic, payload)
    }

    private fun tryBound(tls: Boolean, topic: String, payload: String): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
            try {
                val socket: Socket = if (tls) {
                    val sc = SSLContext.getInstance("TLS")
                    sc.init(null, arrayOf(TrustAll()), SecureRandom())
                    val s = sc.socketFactory.createSocket() as SSLSocket
                    network.bindSocket(s)
                    s.connect(InetSocketAddress(host, PORT), 4000)
                    s.soTimeout = 4000
                    s.startHandshake()
                    s
                } else {
                    val s = Socket()
                    network.bindSocket(s)
                    s.connect(InetSocketAddress(host, PORT), 4000)
                    s.soTimeout = 4000
                    s
                }
                if (talk(socket, topic, payload)) return true
            } catch (_: Exception) { continue }
        }
        return false
    }

    private fun tryDirect(tls: Boolean, topic: String, payload: String): Boolean {
        return try {
            val socket: Socket = if (tls) {
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, arrayOf(TrustAll()), SecureRandom())
                val s = sc.socketFactory.createSocket(host, PORT) as SSLSocket
                s.soTimeout = 4000
                s.startHandshake()
                s
            } else {
                val s = Socket(host, PORT)
                s.soTimeout = 4000
                s
            }
            talk(socket, topic, payload)
        } catch (_: Exception) { false }
    }

    private fun talk(socket: Socket, topic: String, payload: String): Boolean {
        return try {
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            out.write(connectPacket()); out.flush()
            val ack = ByteArray(4)
            val r = inp.read(ack)
            if (r < 2 || ack[0] != 0x20.toByte()) { socket.close(); return false }
            out.write(publishPacket(topic, payload)); out.flush()
            Thread.sleep(600)
            socket.close()
            true
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
            false
        }
    }

    private fun connectPacket(): ByteArray {
        val varHeader = byteArrayOf(
            0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
            4, 0b11000010.toByte(), 0, 60
        )
        val payload = str(CLIENT_ID) + str(USERNAME) + str(PASSWORD)
        val len = varHeader.size + payload.size
        return byteArrayOf(0x10.toByte()) + varLen(len) + varHeader + payload
    }

    private fun publishPacket(topic: String, msg: String): ByteArray {
        val t = str(topic)
        val m = msg.toByteArray()
        return byteArrayOf(0x30) + varLen(t.size + m.size) + t + m
    }

    private fun str(s: String): ByteArray {
        val b = s.toByteArray()
        return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
    }

    private fun varLen(len: Int): ByteArray {
        var n = len
        val out = mutableListOf<Byte>()
        do {
            var b = n % 128
            n /= 128
            if (n > 0) b = b or 0x80
            out.add(b.toByte())
        } while (n > 0)
        return out.toByteArray()
    }

    inner class TrustAll : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
