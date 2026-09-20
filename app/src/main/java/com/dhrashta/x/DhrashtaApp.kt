package com.dhrashta.x

import android.app.Application
import com.dhrashta.x.data.EventLogger

class DhrashtaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLogger.init(this)
    }
}
