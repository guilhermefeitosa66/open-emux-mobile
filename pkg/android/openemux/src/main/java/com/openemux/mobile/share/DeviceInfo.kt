package com.openemux.mobile.share

import android.content.Context
import android.os.Build
import android.provider.Settings

/** The defaults of the device identity: what the phone is called and what it is. */
object DeviceInfo {
    /** The name the user gave the phone in the system settings, else its model, else "Android". */
    fun defaultName(settingsName: String?, model: String?): String =
        sanitizeName(settingsName).ifEmpty { sanitizeName(model) }.ifEmpty { "Android" }

    /** `tablet` from 600 dp of smallest width, the usual tablet line. */
    fun platformFor(smallestScreenWidthDp: Int): String = if (smallestScreenWidthDp >= 600) "tablet" else "phone"

    fun read(context: Context) = IdentityStore.Defaults(
        defaultName(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME), Build.MODEL),
        platformFor(context.resources.configuration.smallestScreenWidthDp),
    )
}
