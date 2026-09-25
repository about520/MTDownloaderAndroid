package com.mtdownloader.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

data class Peer(val name: String, val host: String, val port: Int)

/**
 * 局域网设备发现。Android 侧用系统自带的 NsdManager（就是 Bonjour/mDNS）。
 *
 * 服务类型必须和 iOS 端一致（_mtdl._tcp），否则两边互相看不见。
 */
class LanDiscovery(private val context: Context) {

    companion object {
        /** iOS 端 NWListener.Service 用的是 "_mtdl._tcp"，Android 习惯带尾点，等价 */
        const val SERVICE_TYPE = "_mtdl._tcp."

        fun deviceName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            val brand = Build.MANUFACTURER?.trim().orEmpty()
            val n = if (model.startsWith(brand, true)) model else "$brand $model".trim()
            return if (n.isEmpty()) "Android 设备" else n
        }
    }

    interface Listener {
        fun onPeersChanged(peers: List<Peer>)
        fun onStatus(text: String)
    }

    var listener: Listener? = null

    private val nsd: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val peers = LinkedHashMap<String, Peer>()

    @Volatile private var discovering = false
    @Volatile private var registered = false

    private fun notifyChanged() {
        val list = peers.values.toList()
        listener?.onPeersChanged(list)
    }

    // MARK: 广播自己

    fun register(port: Int) {
        if (registered) return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD,
                object : NsdManager.RegistrationListener {
                    override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) {
                        registered = false
                        listener?.onStatus("广播失败（错误码 $code）")
                    }
                    override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
                    override fun onServiceRegistered(s: NsdServiceInfo) {
                        registered = true
                    }
                    override fun onServiceUnregistered(s: NsdServiceInfo) {
                        registered = false
                    }
                })
        } catch (e: Exception) {
            listener?.onStatus("广播失败：${e.message}")
        }
    }

    fun unregister() {
        if (!registered) return
        try {
            nsd.unregisterService(object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
                override fun onServiceRegistered(s: NsdServiceInfo) {}
                override fun onServiceUnregistered(s: NsdServiceInfo) {
                    registered = false
                }
            })
        } catch (_: Exception) {
        }
        registered = false
    }

    // MARK: 发现别人

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
            listener?.onStatus("有设备解析失败（错误码 $code）")
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            peers[info.serviceName] = Peer(info.serviceName, host, info.port)
            notifyChanged()
        }
    }

    fun discover() {
        if (discovering) {
            // 已经在扫了，把已有结果再推一次
            notifyChanged()
            return
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
                object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(t: String, code: Int) {
                        discovering = false
                        listener?.onStatus("扫描启动失败（错误码 $code）")
                    }
                    override fun onStopDiscoveryFailed(t: String, code: Int) {
                        discovering = false
                    }
                    override fun onDiscoveryStarted(t: String) {
                        discovering = true
                        listener?.onStatus("扫描中…")
                    }
                    override fun onDiscoveryStopped(t: String) {
                        discovering = false
                    }
                    override fun onServiceFound(info: NsdServiceInfo) {
                        try {
                            nsd.resolveService(info, resolveListener)
                        } catch (_: Exception) {
                        }
                    }
                    override fun onServiceLost(info: NsdServiceInfo) {
                        peers.remove(info.serviceName)
                        notifyChanged()
                    }
                })
        } catch (e: Exception) {
            listener?.onStatus("扫描失败：${e.message}")
        }
    }

    fun stopDiscover() {
        if (!discovering) return
        try {
            nsd.stopServiceDiscovery(object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(t: String, code: Int) {}
                override fun onStopDiscoveryFailed(t: String, code: Int) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {
                    discovering = false
                }
                override fun onServiceFound(info: NsdServiceInfo) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
            })
        } catch (_: Exception) {
        }
    }
}
