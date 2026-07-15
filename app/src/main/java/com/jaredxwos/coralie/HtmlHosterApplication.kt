package com.jaredxwos.coralie

import android.app.Application
import com.jaredxwos.coralie.connection.manager.buildLiveConnectionManager
import com.jaredxwos.coralie.connection.manager.initMeshRuntime
import com.jaredxwos.coralie.mesh.AppMesh
import com.jaredxwos.coralie.storage.AppDatabase
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.utility.PersistentUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HtmlHosterApplication : Application() {

    // App-lifetime scope: owns the mesh subsystem overall. Individual
    // ConnectionManagers built per page load are siblings under this, per
    // LiveConnectionManager's parentScope doc comment.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        AppStorage.init(db.appDao(), filesDir)
        PersistentUri.init(contentResolver)
        initMeshRuntime(this)
        AppMesh.configure { buildLiveConnectionManager(appScope) }
    }
}