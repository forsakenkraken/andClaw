package com.coderred.andclaw

import android.app.Application
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.proroot.OpenClawModelCatalogReader
import com.coderred.andclaw.proroot.ProcessManager
import com.coderred.andclaw.proroot.ProrootManager
import com.coderred.andclaw.proroot.SetupManager
import com.coderred.andclaw.service.GatewayService

class AndClawApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set
    lateinit var prorootManager: ProrootManager
        private set
    lateinit var setupManager: SetupManager
        private set
    lateinit var processManager: ProcessManager
        private set

    override fun onCreate() {
        super.onCreate()
        OpenClawModelCatalogReader.init(this)
        preferencesManager = PreferencesManager(this)
        prorootManager = ProrootManager(this)
        prorootManager.prepareSelectedRuntime()
        setupManager = SetupManager(this, prorootManager, preferencesManager)
        processManager = ProcessManager(prorootManager)
        GatewayService.bindRetainedProcessManager(processManager)
    }
}
