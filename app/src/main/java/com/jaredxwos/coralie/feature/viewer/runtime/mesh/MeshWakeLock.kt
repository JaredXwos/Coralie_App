package com.jaredxwos.coralie.feature.viewer.runtime.mesh

import android.content.Context
import android.os.PowerManager

// Just needs: <uses-permission android:name="android.permission.WAKE_LOCK" />

private var meshWakeLock: PowerManager.WakeLock? = null

fun holdMeshAwakeBriefly(context: Context, durationMs: Long = 3 * 60 * 1000L) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    meshWakeLock?.let { if (it.isHeld) it.release() }
    meshWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HtmlHoster::MeshBriefWakeLock").apply {
        acquire(durationMs) // auto-releases after this timeout even if you forget
    }
}

fun releaseMeshWakeLock() {
    meshWakeLock?.let { if (it.isHeld) it.release() }
    meshWakeLock = null
}