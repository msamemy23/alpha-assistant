package com.alphainternational.assistant

import android.app.Application

class AlphaApplication:Application() {
    lateinit var controller:Controller
    override fun onCreate() { super.onCreate(); controller=Controller(this); UiPrefs.load(controller.config("ui")) }
}
