package com.coderred.andclaw

import android.app.Application
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.proot.OpenClawModelCatalogReader
import com.coderred.andclaw.proot.ProcessManager
import com.coderred.andclaw.proot.ProotManager
import com.coderred.andclaw.proot.SetupManager
import com.coderred.andclaw.service.GatewayService

class AndClawApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set
    lateinit var prootManager: ProotManager
        private set
    lateinit var setupManager: SetupManager
        private set
    lateinit var processManager: ProcessManager
        private set

    override fun onCreate() {
        super.onCreate()
        OpenClawModelCatalogReader.init(this)
        preferencesManager = PreferencesManager(this)
        prootManager = ProotManager(this)
        setupManager = SetupManager(this, prootManager, preferencesManager)
        processManager = ProcessManager(prootManager)
        GatewayService.bindRetainedProcessManager(processManager)
    }
}
