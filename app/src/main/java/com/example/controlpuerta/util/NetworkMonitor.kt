package com.example.controlpuerta.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val NETWORK_TAG = "NetworkMonitor"

/**
 * Observa en tiempo real si el dispositivo cuenta con enlace de red operativo para control IoT.
 */
@Composable
fun rememberIsNetworkConnected(): State<Boolean> {
    val context = LocalContext.current
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    val isConnected = remember(connectivityManager) {
        mutableStateOf(checkCurrentInternetConnectivity(connectivityManager))
    }

    DisposableEffect(connectivityManager) {
        if (connectivityManager == null) {
            onDispose { }
        } else {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    isConnected.value = checkCurrentInternetConnectivity(connectivityManager)
                }

                override fun onLost(network: Network) {
                    isConnected.value = checkCurrentInternetConnectivity(connectivityManager)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    isConnected.value =
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            }

            try {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } catch (e: SecurityException) {
                Log.e(NETWORK_TAG, "No fue posible registrar el monitor de red: ${e.message}", e)
            }

            onDispose {
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (e: IllegalArgumentException) {
                    Log.w(NETWORK_TAG, "Callback de red ya desregistrado: ${e.message}")
                }
            }
        }
    }

    return isConnected
}

private fun checkCurrentInternetConnectivity(connectivityManager: ConnectivityManager?): Boolean {
    if (connectivityManager == null) return false
    return try {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: SecurityException) {
        Log.e(NETWORK_TAG, "Permiso insuficiente al consultar conectividad: ${e.message}", e)
        false
    }
}
