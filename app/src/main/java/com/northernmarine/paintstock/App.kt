package com.northernmarine.paintstock

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText("CRASH\n" + e.toString() + "\n\n" + sw.toString())
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
    companion object {
        fun readCrash(ctx: Context): String? = try {
            val f = File(ctx.filesDir, "last_crash.txt"); if (f.exists()) f.readText() else null
        } catch (_: Throwable) { null }
        fun clearCrash(ctx: Context) { try { File(ctx.filesDir, "last_crash.txt").delete() } catch (_: Throwable) {} }
    }
}
