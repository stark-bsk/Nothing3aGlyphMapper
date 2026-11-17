package com.nothing.glyphmapper

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

enum class GlyphStrip(val label: String) {
    STRIP_A("A"),
    STRIP_B("B"),
    STRIP_C("C")
}

data class GlyphSettings(
    val masterEnabled: Boolean = true,
    val pulseMode: Boolean = false,
    val mappings: Map<String, Set<GlyphStrip>> = emptyMap()
)

private val Context.glyphDataStore by preferencesDataStore(name = "glyph_mapper_prefs")

class GlyphPreferences(private val context: Context) {

    private object Keys {
        val MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        val PULSE_MODE = booleanPreferencesKey("pulse_mode")
        val APP_MAPPINGS_JSON = stringPreferencesKey("app_mappings_json")
    }

    val settingsFlow: Flow<GlyphSettings> = context.glyphDataStore.data.map { prefs ->
        val masterEnabled = prefs[Keys.MASTER_ENABLED] ?: true
        val pulseMode = prefs[Keys.PULSE_MODE] ?: false
        val mappingsJson = prefs[Keys.APP_MAPPINGS_JSON] ?: "{}"
        GlyphSettings(
            masterEnabled = masterEnabled,
            pulseMode = pulseMode,
            mappings = parseMappings(mappingsJson)
        )
    }

    suspend fun getSettingsSnapshot(): GlyphSettings = settingsFlow.first()

    suspend fun setMasterEnabled(enabled: Boolean) {
        context.glyphDataStore.edit { prefs ->
            prefs[Keys.MASTER_ENABLED] = enabled
        }
    }

    suspend fun setPulseMode(pulse: Boolean) {
        context.glyphDataStore.edit { prefs ->
            prefs[Keys.PULSE_MODE] = pulse
        }
    }

    suspend fun toggleStripForApp(packageName: String, strip: GlyphStrip) {
        context.glyphDataStore.edit { prefs ->
            val current = parseMappings(prefs[Keys.APP_MAPPINGS_JSON] ?: "{}").toMutableMap()
            val strips = current[packageName]?.toMutableSet() ?: mutableSetOf()
            if (strips.contains(strip)) {
                strips.remove(strip)
            } else {
                strips.add(strip)
            }
            if (strips.isEmpty()) {
                current.remove(packageName)
            } else {
                current[packageName] = strips
            }
            prefs[Keys.APP_MAPPINGS_JSON] = serializeMappings(current)
        }
    }

    private fun parseMappings(json: String): Map<String, Set<GlyphStrip>> {
        val result = mutableMapOf<String, Set<GlyphStrip>>()
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val arr = obj.optJSONArray(pkg) ?: continue
                val strips = mutableSetOf<GlyphStrip>()
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i)
                    runCatching { GlyphStrip.valueOf(name) }.getOrNull()?.let { strips.add(it) }
                }
                if (strips.isNotEmpty()) {
                    result[pkg] = strips
                }
            }
        } catch (_: Exception) {
            // Return empty map if malformed
        }
        return result
    }

    private fun serializeMappings(mappings: Map<String, Set<GlyphStrip>>): String {
        val obj = JSONObject()
        for ((pkg, strips) in mappings) {
            if (strips.isNotEmpty()) {
                val arr = JSONArray()
                strips.forEach { arr.put(it.name) }
                obj.put(pkg, arr)
            }
        }
        return obj.toString()
    }
}
