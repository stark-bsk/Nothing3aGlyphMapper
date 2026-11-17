package com.nothing.glyphmapper

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GlyphNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "GlyphNotifService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var preferences: GlyphPreferences

    @Volatile
    private var currentSettings: GlyphSettings = GlyphSettings()

    @Volatile
    private var isListenerConnected = false

    override fun onCreate() {
        super.onCreate()
        preferences = GlyphPreferences(applicationContext)
        GlyphController.init(applicationContext)

        serviceScope.launch {
            preferences.settingsFlow.collectLatest { settings ->
                currentSettings = settings
                if (isListenerConnected) {
                    evaluateActiveNotifications()
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerConnected = true
        evaluateActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerConnected = false
        GlyphController.turnOffAndClose()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        evaluateActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        evaluateActiveNotifications()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        GlyphController.turnOffAndClose()
        super.onDestroy()
    }

    private fun evaluateActiveNotifications() {
        val settings = currentSettings
        if (!settings.masterEnabled) {
            GlyphController.turnOffAndClose()
            return
        }

        val active = try {
            activeNotifications
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query activeNotifications", e)
            null
        }

        if (active.isNullOrEmpty() || settings.mappings.isEmpty()) {
            GlyphController.turnOffAndClose()
            return
        }

        var stripA = false
        var stripB = false
        var stripC = false

        for (sbn in active) {
            val mappedStrips = settings.mappings[sbn.packageName] ?: continue
            if (GlyphStrip.STRIP_A in mappedStrips) stripA = true
            if (GlyphStrip.STRIP_B in mappedStrips) stripB = true
            if (GlyphStrip.STRIP_C in mappedStrips) stripC = true
            if (stripA && stripB && stripC) break
        }

        if (!stripA && !stripB && !stripC) {
            GlyphController.turnOffAndClose()
        } else {
            GlyphController.updateActiveStrips(
                context = applicationContext,
                stripA = stripA,
                stripB = stripB,
                stripC = stripC,
                pulse = settings.pulseMode
            )
        }
    }
}
