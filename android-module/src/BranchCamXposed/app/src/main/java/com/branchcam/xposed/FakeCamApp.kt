package com.branchcam.xposed

import android.app.Application

class BranchCamApp : Application() {
    companion object {
        lateinit var instance: BranchCamApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
