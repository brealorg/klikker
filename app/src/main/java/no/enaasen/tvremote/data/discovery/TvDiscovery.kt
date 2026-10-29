package no.enaasen.tvremote.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.enaasen.tvremote.data.model.DiscoveredTv

class DiscoveryException(errorCode: Int) : Exception("NSD discovery failed: $errorCode")

/** Finds TVs advertising the Android TV Remote service over mDNS. */
@Singleton
class TvDiscovery @Inject constructor(@ApplicationContext context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    // Before API 34 NsdManager can only resolve one service at a time.
    private val resolveMutex = Mutex()

    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        val found = ConcurrentHashMap<String, DiscoveredTv>()
        fun publish() = trySend(found.values.sortedBy { it.name })

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(DiscoveryException(errorCode))
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                launch {
                    resolve(service)?.let {
                        found[it.name] = it
                        publish()
                    }
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                if (found.remove(service.serviceName) != null) publish()
            }
        }

        publish()
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** Looks up the current address of a TV by its advertised name. */
    suspend fun find(name: String, timeoutMillis: Long): DiscoveredTv? = try {
        withTimeoutOrNull(timeoutMillis) {
            discover().mapNotNull { tvs -> tvs.find { it.name == name } }.first()
        }
    } catch (_: DiscoveryException) {
        null
    }

    private suspend fun resolve(service: NsdServiceInfo): DiscoveredTv? = resolveMutex.withLock {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            val address = info.hostAddresses.firstOrNull { it is Inet4Address }
                                ?: info.hostAddresses.firstOrNull()
                                ?: return
                            if (continuation.isActive) {
                                continuation.resume(DiscoveredTv(info.serviceName, address.hostAddress!!, info.port))
                            }
                            runCatching { nsd.unregisterServiceInfoCallback(this) }
                        }
                        override fun onServiceLost() = Unit
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }
                    nsd.registerServiceInfoCallback(service, Dispatchers.IO.asExecutor(), callback)
                    continuation.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
                } else {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val address = info.host?.hostAddress
                                if (continuation.isActive) {
                                    continuation.resume(address?.let { DiscoveredTv(info.serviceName, it, info.port) })
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
