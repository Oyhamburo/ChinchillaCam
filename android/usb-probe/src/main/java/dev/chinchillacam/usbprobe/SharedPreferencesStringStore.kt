package dev.chinchillacam.usbprobe

import android.content.SharedPreferences

internal class SharedPreferencesStringStore(
    private val preferences: SharedPreferences,
    private val key: String,
) : StringPreferenceStore {
    override fun get(): String? = preferences.getString(key, null)
    override fun put(value: String) {
        preferences.edit().putString(key, value).apply()
    }
    override fun clear() {
        preferences.edit().remove(key).apply()
    }
}
