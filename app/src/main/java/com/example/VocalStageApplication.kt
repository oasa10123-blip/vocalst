package com.example

import android.app.Application
import com.example.audio.AudioDeviceHelper
import com.example.audio.VocalDspEngine
import com.example.data.AppDatabase
import com.example.data.HarmonistRepository
import com.example.data.SettingsPreferences

class AppContainer(application: Application) {
    val database: AppDatabase = AppDatabase.build(application)
    val preferences: SettingsPreferences = SettingsPreferences(application)
    val repository: HarmonistRepository = HarmonistRepository(
        wsRuleDao = database.wsCommandRuleDao(),
        presetDao = database.vocalPresetDao(),
        preferences = preferences
    )
    val deviceHelper: AudioDeviceHelper = AudioDeviceHelper(application)
    val dspEngine: VocalDspEngine = VocalDspEngine(deviceHelper)
    val stageController: StageController = StageController(
        appContext = application,
        repository = repository,
        deviceHelper = deviceHelper,
        dspEngine = dspEngine
    )
}

class VocalStageApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
