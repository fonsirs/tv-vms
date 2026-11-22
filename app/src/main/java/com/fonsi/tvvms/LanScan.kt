package com.fonsi.tvvms

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Busca cámaras y grabadores en la red local: descubrimiento ONVIF (multicast) + sondeo de puertos típicos. */
object LanScan {

    class Found(
        val ip: String,
        val brand: Brand?,
        val ports: List<Int>,
        val name: String,
        val onvif: Boolean
    ) {
        fun label(): String {
            val b = when (brand) {
                Brand.DAHUA -> "Dahua"
                Brand.HIKVISION -> "Hikvision"
                else -> "RTSP / ONVIF"
            }
            val n = if (name.isEmpty()) "" else "   ·   " + name
            val p = if (ports.isEmpty()) "" else "   (puertos " + ports.joinToString(", ") + ")"
            val o = if (onvif) "   ·   ONVIF" else ""
            return ip + "   ·   " + b + n + o + p
        }
    }

    private val PORTS = intArrayOf(554, 37777, 8000, 80)

    /** Tres primeros números de la red de esta TV (por ejemplo 192.168.1), o null si no se detecta. */
    fun detectPrefix(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val parts = (a.hostAddress ?: "").split(".")
                        if (parts.size == 4) return parts[0] + "." + parts[1] + "." + parts[2]
                    }
                }
            }
        } catch (e: Exception) {
            // sin red detectable
        }
        return null
    }

    private fun isOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use {
                it.connect(InetSocketAddress(host, port), 350)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun ipKey(ip: String): Long {
        var v = 0L
        for (p in ip.split(".")) v = v * 256 + (p.toLongOrNull() ?: 0L)
        return v
    }

    fun scan(
        context: Context,
        prefix: String,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): List<Found> {
        val openPorts = ConcurrentHashMap<String, List<Int>>()
        val pool = Executors.newFixedThreadPool(48)
        val latch = CountDownLatch(254)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        for (i in 1..254) {
            val ip = prefix + "." + i
            pool.execute(Runnable {
                try {
                    if (!isCancelled()) {
                        val open = ArrayList<Int>()
                        for (p in PORTS) {
                            if (isCancelled()) break
                            if (isOpen(ip, p)) open.add(p)
                        }
                        if (open.isNotEmpty()) openPorts[ip] = open
                    }
                } finally {
                    latch.countDown()
                    onProgress(done.incrementAndGet() * 100 / 254)
                }
            })
        }
        latch.await()
        pool.shutdown()

        val onvif = if (isCancelled()) emptyMap() else discoverOnvif(context)

        val all = HashSet<String>()
        all.addAll(openPorts.keys)
        all.addAll(onvif.keys)
        val out = ArrayList<Found>()
        for (ip in all.sortedBy { ipKey(it) }) {
            val ports = openPorts[ip] ?: emptyList()
            val cameraLike = ports.contains(554) || ports.contains(37777) || ports.contains(8000)
            val isOnvif = onvif.containsKey(ip)
            if (!cameraLike && !isOnvif) continue
            val brand = when {
                ports.contains(37777) -> Brand.DAHUA
                ports.contains(8000) -> Brand.HIKVISION
                else -> null
            }
            out.add(Found(ip, brand, ports, onvif[ip] ?: "", isOnvif))
        }
        return out
    }

    private fun probeMessage(): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\"" +
            " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"" +
            " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\"" +
            " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
            "<e:Header><w:MessageID>uuid:" + UUID.randomUUID().toString() + "</w:MessageID>" +
            "<w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
            "<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
            "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"
    }

    /** WS-Discovery de ONVIF: devuelve ip -> nombre del equipo. */
    private fun discoverOnvif(context: Context): Map<String, String> {
        val out = HashMap<String, String>()
        var lock: WifiManager.MulticastLock? = null
        var socket: MulticastSocket? = null
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wm?.createMulticastLock("tvvms")
            lock?.setReferenceCounted(false)
            lock?.acquire()

            val s = MulticastSocket()
            socket = s
            s.soTimeout = 600
            val msg = probeMessage().toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 3702))

            val ipRegex = Regex("https?://([0-9]{1,3}(?:\\.[0-9]{1,3}){3})")
            val nameRegex = Regex("onvif://www\\.onvif\\.org/name/([^\\s<]+)")
            val end = System.currentTimeMillis() + 3000L
            val buf = ByteArray(16384)
            while (System.currentTimeMillis() < end) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    val txt = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val ip = ipRegex.find(txt)?.groupValues?.get(1) ?: pkt.address.hostAddress
                    var name = nameRegex.find(txt)?.groupValues?.get(1) ?: ""
                    try {
                        name = URLDecoder.decode(name, "UTF-8")
                    } catch (e: Exception) {
                        // se deja tal cual
                    }
                    if (ip != null) out[ip] = name
                } catch (e: SocketTimeoutException) {
                    // seguimos esperando hasta el límite
                }
            }
        } catch (e: Exception) {
            // sin multicast: nos quedamos con el sondeo de puertos
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
            }
            try {
                lock?.release()
            } catch (e: Exception) {
            }
        }
        return out
    }
}
