package com.example.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.geometry.Offset

class WindowManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("window_prefs", Context.MODE_PRIVATE)

    fun savePosition(windowId: String, offset: Offset) {
        prefs.edit()
            .putFloat("${windowId}_x", offset.x)
            .putFloat("${windowId}_y", offset.y)
            .apply()
    }

    fun getPosition(windowId: String, defaultOffset: Offset): Offset {
        val x = prefs.getFloat("${windowId}_x", -1f)
        val y = prefs.getFloat("${windowId}_y", -1f)
        return if (x == -1f || y == -1f) defaultOffset else Offset(x, y)
    }
}
