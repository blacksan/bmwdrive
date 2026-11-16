package com.blacksan.bmwdrive.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.blacksan.bmwdrive.transport.EnetConfig
import com.blacksan.bmwdrive.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Speed display unit for dashboards. Internally everything stays km/h. */
enum class SpeedUnit(val label: String, val factor: Float) {
    KMH("km/h", 1f),
    MPH("mph", 0.621371f);

    /** Convert a km/h value into this unit. */
    fun fromKmh(kmh: Float): Float = kmh * factor
}

/** DoIP connection parameters edited in Settings. Defaults mirror [EnetConfig]. */
data class DoipSettings(
    val gatewayIp: String = EnetConfig.DEFAULT_GATEWAY_IP,
    val port: Int = EnetConfig.DOIP_PORT,
    val testerAddress: Int = EnetConfig.DEFAULT_TESTER_ADDRESS,
) {
    /** Build the transport config for these settings. */
    fun toEnetConfig(demoMode: Boolean = false) = EnetConfig(
        gatewayIp = gatewayIp,
        doipPort = port,
        sourceAddress = testerAddress,
        demoMode = demoMode,
    )
}

/** Snapshot of all persisted user settings. */
data class AppSettings(
    val theme: AppTheme = AppTheme.DEFAULT,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val demoMode: Boolean = true,
    val doip: DoipSettings = DoipSettings(),
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "bmwdrive_settings")

/** DataStore-backed settings; values survive process death. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val UNIT = stringPreferencesKey("speed_unit")
        val DEMO = booleanPreferencesKey("demo_mode")
        val IP = stringPreferencesKey("doip_ip")
        val PORT = intPreferencesKey("doip_port")
        val TESTER = intPreferencesKey("doip_tester")
    }

    /** All settings as a single flow. */
    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    val theme: Flow<AppTheme> = settings.map { it.theme }
    val speedUnit: Flow<SpeedUnit> = settings.map { it.speedUnit }
    val demoMode: Flow<Boolean> = settings.map { it.demoMode }
    val doip: Flow<DoipSettings> = settings.map { it.doip }

    suspend fun setTheme(theme: AppTheme) = store.edit { it[Keys.THEME] = theme.name }
    suspend fun setSpeedUnit(unit: SpeedUnit) = store.edit { it[Keys.UNIT] = unit.name }
    suspend fun setDemoMode(enabled: Boolean) = store.edit { it[Keys.DEMO] = enabled }

    suspend fun setDoip(doip: DoipSettings) = store.edit {
        it[Keys.IP] = doip.gatewayIp
        it[Keys.PORT] = doip.port
        it[Keys.TESTER] = doip.testerAddress
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = DoipSettings()
        return AppSettings(
            theme = this[Keys.THEME]?.let { n -> AppTheme.entries.firstOrNull { it.name == n } } ?: AppTheme.DEFAULT,
            speedUnit = this[Keys.UNIT]?.let { n -> SpeedUnit.entries.firstOrNull { it.name == n } } ?: SpeedUnit.KMH,
            demoMode = this[Keys.DEMO] ?: true,
            doip = DoipSettings(
                gatewayIp = this[Keys.IP] ?: d.gatewayIp,
                port = this[Keys.PORT] ?: d.port,
                testerAddress = this[Keys.TESTER] ?: d.testerAddress,
            ),
        )
    }
}
