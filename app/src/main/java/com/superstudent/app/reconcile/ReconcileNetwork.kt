package com.superstudent.app.reconcile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Both source rules only make sense online, and for the same reason: reaching a cloud-backed document
 * provider or Drive while offline looks exactly like "the entry is gone", so being offline is a
 * reason to defer the pass, not a reason to rewrite state.
 */
internal fun hasValidatedNetwork(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val network = cm?.activeNetwork ?: return false
    return cm.getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}
