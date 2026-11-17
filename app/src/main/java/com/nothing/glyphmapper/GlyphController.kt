package com.nothing.glyphmapper

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager

/**
 * Singleton controller for the Nothing Phone (3a) / (3a) Pro strip-based Glyph Interface.
 *
 * Uses KetchumSDK's GlyphManager, GlyphFrame, Glyph.Code_24111, and Common.is24111().
 */
object GlyphController {

    private const val TAG = "GlyphController"

    private val STRIP_A_CHANNELS = intArrayOf(
        Glyph.Code_24111.A1,
        Glyph.Code_24111.A2,
        Glyph.Code_24111.A3,
        Glyph.Code_24111.A4,
        Glyph.Code_24111.A5,
        Glyph.Code_24111.A6,
        Glyph.Code_24111.A7,
        Glyph.Code_24111.A8,
        Glyph.Code_24111.A9,
        Glyph.Code_24111.A10,
        Glyph.Code_24111.A11
    )

    private val STRIP_B_CHANNELS = intArrayOf(
        Glyph.Code_24111.B1,
        Glyph.Code_24111.B2,
        Glyph.Code_24111.B3,
        Glyph.Code_24111.B4,
        Glyph.Code_24111.B5
    )

    private val STRIP_C_CHANNELS = intArrayOf(
        Glyph.Code_24111.C1,
        Glyph.Code_24111.C2,
        Glyph.Code_24111.C3,
        Glyph.Code_24111.C4,
        Glyph.Code_24111.C5,
        Glyph.Code_24111.C6,
        Glyph.Code_24111.C7,
        Glyph.Code_24111.C8,
        Glyph.Code_24111.C9,
        Glyph.Code_24111.C10,
        Glyph.Code_24111.C11,
        Glyph.Code_24111.C12,
        Glyph.Code_24111.C13,
        Glyph.Code_24111.C14,
        Glyph.Code_24111.C15,
        Glyph.Code_24111.C16,
        Glyph.Code_24111.C17,
        Glyph.Code_24111.C18,
        Glyph.Code_24111.C19,
        Glyph.Code_24111.C20
    )

    private data class StripState(
        val stripA: Boolean,
        val stripB: Boolean,
        val stripC: Boolean,
        val pulse: Boolean
    )

    private val lock = Any()

    @Volatile
    private var glyphManager: GlyphManager? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isServiceConnected = false

    @Volatile
    private var isSessionOpen = false

    @Volatile
    private var pendingState: StripState? = null

    private val callback = object : GlyphManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            synchronized(lock) {
                isServiceConnected = true
                val gm = glyphManager ?: return
                try {
                    if (Common.is24111()) {
                        gm.register(Glyph.DEVICE_24111)
                    } else {
                        Log.w(TAG, "Device is not Phone (3a) [24111]; attempting DEVICE_24111 registration.")
                        gm.register(Glyph.DEVICE_24111)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register GlyphManager for DEVICE_24111", e)
                }

                val pending = pendingState
                if (pending != null) {
                    pendingState = null
                    applyStripStateLocked(pending)
                }
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            synchronized(lock) {
                isServiceConnected = false
                if (isSessionOpen) {
                    try {
                        glyphManager?.closeSession()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing session on disconnect", e)
                    }
                }
                isSessionOpen = false
            }
        }
    }

    fun init(context: Context) {
        synchronized(lock) {
            if (isInitialized && glyphManager != null) return
            try {
                val gm = GlyphManager.getInstance(context.applicationContext)
                glyphManager = gm
                gm.init(callback)
                isInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize GlyphManager", e)
                isInitialized = false
            }
        }
    }

    fun isPhone3a(): Boolean = try {
        Common.is24111()
    } catch (e: Exception) {
        false
    }

    /**
     * Combines whichever strips are active into a single GlyphFrame and illuminates or pulses them.
     * If all strips are false, turns off the Glyphs and closes the active session.
     */
    fun updateActiveStrips(
        context: Context,
        stripA: Boolean,
        stripB: Boolean,
        stripC: Boolean,
        pulse: Boolean
    ) {
        if (!stripA && !stripB && !stripC) {
            turnOffAndClose()
            return
        }

        synchronized(lock) {
            if (!isInitialized || glyphManager == null) {
                pendingState = StripState(stripA, stripB, stripC, pulse)
                init(context)
                return
            }

            if (!isServiceConnected) {
                pendingState = StripState(stripA, stripB, stripC, pulse)
                return
            }

            applyStripStateLocked(StripState(stripA, stripB, stripC, pulse))
        }
    }

    private fun applyStripStateLocked(state: StripState) {
        val gm = glyphManager ?: return
        if (!isServiceConnected) return

        try {
            if (!isSessionOpen) {
                gm.openSession()
                isSessionOpen = true
            }

            val builder = gm.getGlyphFrameBuilder() ?: GlyphFrame.Builder(Glyph.DEVICE_24111)

            if (state.stripA) {
                for (channel in STRIP_A_CHANNELS) {
                    builder.buildChannel(channel)
                }
            }
            if (state.stripB) {
                for (channel in STRIP_B_CHANNELS) {
                    builder.buildChannel(channel)
                }
            }
            if (state.stripC) {
                for (channel in STRIP_C_CHANNELS) {
                    builder.buildChannel(channel)
                }
            }

            if (state.pulse) {
                builder
                    .buildPeriod(2000)
                    .buildInterval(300)
                    .buildCycles(500)
                val frame = builder.build()
                gm.animate(frame)
            } else {
                val frame = builder.build()
                gm.toggle(frame)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating active Glyph strips", e)
        }
    }

    fun turnOffAndClose() {
        synchronized(lock) {
            pendingState = null
            val gm = glyphManager ?: return
            try {
                if (isServiceConnected && isSessionOpen) {
                    gm.turnOff()
                    gm.closeSession()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error turning off / closing Glyph session", e)
            } finally {
                isSessionOpen = false
            }
        }
    }

    fun unInit() {
        synchronized(lock) {
            turnOffAndClose()
            try {
                if (isInitialized) {
                    glyphManager?.unInit()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uninitializing GlyphManager", e)
            } finally {
                isInitialized = false
                isServiceConnected = false
                glyphManager = null
            }
        }
    }
}
