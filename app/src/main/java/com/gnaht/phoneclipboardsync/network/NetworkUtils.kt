package com.gnaht.phoneclipboardsync.network

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun findLocalIpv4Address(): String {
        return Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { networkInterface ->
                Collections.list(networkInterface.inetAddresses)
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .map { address -> NetworkAddressCandidate(networkInterface, address) }
            }
            .sortedWith(
                compareBy<NetworkAddressCandidate> { it.interfacePriority }
                    .thenBy { if (it.address.isSiteLocalAddress) 0 else 1 },
            )
            .firstOrNull()
            ?.address
            ?.hostAddress
            .orEmpty()
    }

    private data class NetworkAddressCandidate(
        val networkInterface: NetworkInterface,
        val address: Inet4Address,
    ) {
        val interfacePriority: Int
            get() {
                val name = networkInterface.name.lowercase()
                return when {
                    name.startsWith("wlan") || name.startsWith("wifi") -> 0
                    name.startsWith("eth") || name.startsWith("en") -> 1
                    name.startsWith("ap") -> 2
                    else -> 3
                }
            }
    }
}
