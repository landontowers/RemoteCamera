package com.example.remotecamera.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

class NsdHelper(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    companion object {
        const val SERVICE_TYPE = "_remotecamera._tcp"
        const val SERVICE_NAME = "PoleCameraRemote"
        private const val TAG = "NsdHelper"
        private const val PREFS_NAME = "nsd_helper_prefs"
        private const val KEY_INSTANCE_ID = "device_instance_id"
    }

    // A short id generated once and persisted per app install, so two devices of the
    // identical model don't broadcast an identical service name — the device picker's
    // discovered-server list and its onServiceLost cleanup both key off this name being
    // unique per physical device.
    private fun getInstanceId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_INSTANCE_ID, null) ?: run {
            val id = java.util.UUID.randomUUID().toString().take(6).uppercase()
            prefs.edit().putString(KEY_INSTANCE_ID, id).apply()
            id
        }
    }

    // Callbacks for discovery
    var onServiceResolved: ((serviceName: String, hostIp: String, port: Int) -> Unit)? = null
    var onServiceLost: ((serviceName: String) -> Unit)? = null
    var onDiscoveryStarted: (() -> Unit)? = null
    var onDiscoveryStopped: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun registerService(port: Int) {
        // Include the device model so multiple servers on the same network are
        // distinguishable in the controller's device picker instead of all showing
        // up with the identical name, and a per-install instance id so two devices
        // of the same model don't collide on an identical name.
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = "$SERVICE_NAME (${Build.MODEL}-${getInstanceId()})"
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${info.serviceName} on port ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service registration failed with error code $errorCode")
                onError?.invoke("Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed with error code $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering service", e)
            onError?.invoke(e.message ?: "Unknown error during registration")
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering service", e)
            }
            registrationListener = null
        }
    }

    fun discoverServices() {
        stopDiscovery()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to start: $errorCode")
                nsdManager.stopServiceDiscovery(this)
                onError?.invoke("Discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to stop: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Service discovery started: $serviceType")
                onDiscoveryStarted?.invoke()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Service discovery stopped: $serviceType")
                onDiscoveryStopped?.invoke()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: $serviceInfo")
                // Check serviceType match (nsdManager can return with or without training dot depending on version)
                if (serviceInfo.serviceType.contains(SERVICE_TYPE)) {
                    Log.d(TAG, "Resolving service: ${serviceInfo.serviceName}")
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.e(TAG, "Service lost: $serviceInfo")
                onServiceLost?.invoke(serviceInfo.serviceName)
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery", e)
            onError?.invoke(e.message ?: "Unknown error during discovery start")
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
            discoveryListener = null
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode for ${info.serviceName}")
                onError?.invoke("Resolve failed: $errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                Log.d(TAG, "Resolve Succeeded. Name: ${info.serviceName}, IP: ${info.host}, Port: ${info.port}")
                val hostIp = info.host?.hostAddress ?: return
                onServiceResolved?.invoke(info.serviceName, hostIp, info.port)
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
        }
    }
}
