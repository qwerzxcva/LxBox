package com.leadaxe.aibox.engine.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor

import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner

import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState

/** IP protocol numbers as the core passes them to [findConnectionOwner]. */
private const val IPPROTO_TCP_NUM = 6
private const val IPPROTO_UDP_NUM = 17

/** Minimal [StringIterator] over an in-memory list, for gomobile callbacks. */
private class ListStringIterator(private val values: List<String>) : StringIterator {
    private var index = 0
    override fun len(): Int = values.size
    override fun hasNext(): Boolean = index < values.size
    override fun next(): String = values[index++]
}

/**
 * Bridge between libbox's [PlatformInterface] and the Android runtime.
 *
 * Most callbacks are no-ops on Android (no real SSH agent, no `proc` fs, no
 * USB/IP plumbing for a VPN client). The two that matter are
 * [openTun] — we hand back the file descriptor of the tun interface the
 * VPN service has already established — and [sendNotification] — we surface
 * libbox-emitted notifications through the standard
 * `NotificationManager`.
 */
class AIPlatform(private val context: Context) : PlatformInterface {

    @Volatile
    private var currentTun: ParcelFileDescriptor? = null

    /** Set by the VPN service right after `VpnService.Builder#establish()`. */
    internal fun setTun(fd: ParcelFileDescriptor) {
        currentTun = fd
    }

    /**
     * Called by [BoxEngine] when libbox was supposed to take ownership of
     * the fd but the call path failed *before* [openTunFromEngine] ran. We
     * close the fd so the kernel tun interface doesn't leak; libbox never
     * reached it. Safe to call when the fd is already null.
     */
    internal fun discardTun() {
        val fd = currentTun ?: return
        runCatching { fd.close() }
        currentTun = null
    }

    internal fun clearTun() {
        runCatching { currentTun?.close() }
        currentTun = null
    }

    /** Used by [BoxEngine] when libbox asks for the tun fd. */
    internal fun openTunFromEngine(): Int {
        val fd = currentTun ?: return -1
        // Detach so libbox owns the fd; closing our descriptor handle would
        // also close the kernel tun.
        val raw = fd.detachFd()
        return raw
    }

    // -- PlatformInterface ----------------------------------------------------

    override fun openTun(options: TunOptions): Int = openTunFromEngine()

    override fun useProcFS(): Boolean = false
    override fun usePlatformAutoRedirect(): Boolean = false
    override fun usePlatformBridge(): Boolean = false
    override fun usePlatformShell(): Boolean = false
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun tailscaleHostname(): String = ""

    override fun clearDNSCache() = Unit

    override fun lookupSFTPServer(): String = throw UnsupportedOperationException("no SFTP")
    override fun lookupUser(name: String): PlatformUser = throw UnsupportedOperationException("no users")
    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("no SSH")

    override fun readWIFIState(): WIFIState = throw UnsupportedOperationException("no WIFI state on Android")

    override fun registerMyInterface(name: String) = Unit

    override fun checkPlatformShell() = Unit

    override fun autoDetectInterfaceControl(fd: Int) = Unit

    override fun sendNotification(notification: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        val builder = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(notification.title.orEmpty())
            .setContentText(notification.body.orEmpty())
            .setSubText(notification.subtitle.takeUnless { it.isNullOrEmpty() })
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
        notification.openURL.takeUnless { it.isNullOrEmpty() }?.let {
            // The PendingIntent is filled in by the service once we know the
            // launch activity — keep it null here for now.
        }
        runCatching { nm.notify(notification.identifier.orEmpty().hashCode(), builder.build()) }
    }

    override fun cancelNotification(id: String, type: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(id.hashCode()) }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = collectInterfaces()
        return object : NetworkInterfaceIterator {
            private var idx = 0
            override fun hasNext(): Boolean = idx < list.size
            override fun next(): NetworkInterface = list[idx++]
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // We don't auto-rebind on network changes yet; the VPN service handles
        // its own reconnect via Android's NetworkCallback if needed.
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun createBridge(options: BridgeOptions): BridgeSession =
        throw UnsupportedOperationException("no bridges on Android")

    override fun createAutoRedirect(config: ByteArray, handler: AutoRedirectHandler): AutoRedirectSession =
        throw UnsupportedOperationException("no auto-redirect on Android")

    override fun findConnectionOwner(
        ipVersion: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int,
    ): ConnectionOwner? {
        // ConnectivityManager.getConnectionOwnerUid is API 29+; below that we
        // cannot attribute connections without root, so return null and the
        // connection shows up without an owner.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val protocol = if (ipVersion == IPPROTO_UDP_NUM) IPPROTO_UDP_NUM else IPPROTO_TCP_NUM
        val uid = runCatching {
            cm.getConnectionOwnerUid(
                protocol,
                java.net.InetSocketAddress(sourceAddress, sourcePort),
                java.net.InetSocketAddress(destinationAddress, destinationPort),
            )
        }.getOrNull() ?: return null
        if (uid <= 0) return null
        return ConnectionOwner().apply {
            setUserId(uid)
            val pm = context.packageManager
            val packages = runCatching {
                pm.getPackagesForUid(uid)?.toList().orEmpty()
            }.getOrDefault(emptyList())
            if (packages.isNotEmpty()) {
                setAndroidPackageNames(ListStringIterator(packages))
            }
        }
    }

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun openShellSession(
        user: PlatformUser, command: String, arguments: StringIterator, workingDirectory: String, fd: Int, pid: Int,
    ): ShellSession = throw UnsupportedOperationException("no shell sessions on Android")

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "sing-box",
                NotificationManager.IMPORTANCE_LOW,
            )
            ch.description = "Background notifications from the AIBox VPN engine."
            nm.createNotificationChannel(ch)
        }
    }

    private fun collectInterfaces(): List<NetworkInterface> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val out = mutableListOf<NetworkInterface>()
        val networks = cm.allNetworks
        for (n in networks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            val link = cm.getLinkProperties(n) ?: continue
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> io.nekohasekai.libbox.Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet
                else -> io.nekohasekai.libbox.Libbox.InterfaceTypeOther
            }
            val addrIter = object : StringIterator {
                private val iter = link.linkAddresses.map { it.address.hostAddress.orEmpty() }.iterator()
                override fun hasNext() = iter.hasNext()
                override fun len() = link.linkAddresses.size
                override fun next() = iter.next()
            }
            val dnsIter = object : StringIterator {
                private val iter = link.dnsServers.map { it.hostAddress.orEmpty() }.iterator()
                override fun hasNext() = iter.hasNext()
                override fun len() = link.dnsServers.size
                override fun next() = iter.next()
            }
            out += NetworkInterface().apply {
                setName(n.toString())
                setAddresses(addrIter)
                setDNSServer(dnsIter)
                setMTU(1500)
                setType(type)
            }
        }
        return out
    }

    companion object {
        private const val CHANNEL_ID = "lxbox.engine"
    }
}

