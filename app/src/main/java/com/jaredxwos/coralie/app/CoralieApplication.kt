package com.jaredxwos.coralie.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.jaredxwos.coralie.connection.manager.buildLiveConnectionManager
import com.jaredxwos.coralie.connection.manager.initMeshRuntime
import com.jaredxwos.coralie.feature.viewer.runtime.mesh.AppMesh
import com.jaredxwos.coralie.feature.viewer.runtime.mesh.holdMeshAwakeBriefly
import com.jaredxwos.coralie.feature.viewer.runtime.mesh.releaseMeshWakeLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CoralieApplication : Application() {
    private val appScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default,
        )

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(this)

        ProcessLifecycleOwner
            .get()
            .lifecycle
            .addObserver(
                object :
                    DefaultLifecycleObserver {
                    override fun onStop(
                        owner: LifecycleOwner,
                    ) {
                        holdMeshAwakeBriefly(
                            this@CoralieApplication,
                        )
                    }

                    override fun onStart(
                        owner: LifecycleOwner,
                    ) {
                        releaseMeshWakeLock()
                    }
                },
            )

        initMeshRuntime(this)
        AppMesh.configure {
            buildLiveConnectionManager(
                appScope,
            )
        }
    }
}
