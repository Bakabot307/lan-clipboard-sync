package com.gnaht.phoneclipboardsync.network

import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun findLocalIpv4Address(): String {
        return Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { networkInterface -> Collections.list(networkInterface.inetAddresses) }
            .firstOrNull { address ->
                !address.isLoopbackAddress && address.hostAddress?.contains(":") == false
            }
            ?.hostAddress
            .orEmpty()
    }
}
