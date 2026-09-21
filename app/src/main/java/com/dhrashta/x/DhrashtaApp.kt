package com.dhrashta.x

import android.app.Application
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.enforcement.CanaryManager

class DhrashtaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLogger.init(this)
        CanaryManager.plantCanaries(this)
    }
}
