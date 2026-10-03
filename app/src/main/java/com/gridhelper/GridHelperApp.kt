package com.gridhelper

import android.app.Application
import android.content.Context
import com.gridhelper.core.SettingsRepository
import com.gridhelper.core.ShapeStatsRepository

class GridHelperApp : Application() {

    lateinit var settings: SettingsRepository
        private set
    lateinit var shapeStats: ShapeStatsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        shapeStats = ShapeStatsRepository(this)
    }

    companion object {
        fun from(context: Context): GridHelperApp = context.applicationContext as GridHelperApp
    }
}
