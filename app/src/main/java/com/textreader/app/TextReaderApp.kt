package com.textreader.app

import android.app.Application
import android.content.Context
import com.textreader.app.player.Reader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TextReaderApp : Application() {

    lateinit var settings: Settings
        private set

    lateinit var reader: Reader
        private set

    /** Long-running work (like loading a big PDF) that should survive closing the screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        reader = Reader(this, settings)
        PDFBoxResourceLoader.init(applicationContext)
    }
}

val Context.app: TextReaderApp
    get() = applicationContext as TextReaderApp
