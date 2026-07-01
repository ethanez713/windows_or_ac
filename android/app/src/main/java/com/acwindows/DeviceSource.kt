package com.acwindows

/**
 * A source of home/device state — the Phase-2 seam for smart-home integration.
 *
 * The weather sources in [WeatherRepository] tell us the *outdoor* conditions;
 * a DeviceSource will tell us the *indoor* / equipment state (a Google Home
 * thermostat's indoor temperature + setpoint, the attic fan's on/off state, and
 * eventually control of them). Keeping this behind an interface means the
 * decision engine and the app UI can consume [HomeState] today (empty) and gain
 * real data later without structural change.
 *
 * MVP ships only [NoopDeviceSource]; no network, no credentials, no control.
 */
interface DeviceSource {
    /** Read the current home/device state. */
    suspend fun read(cfg: AppConfig): HomeState
}

/** The MVP default: no devices connected, so home state is empty/unknown. */
object NoopDeviceSource : DeviceSource {
    override suspend fun read(cfg: AppConfig): HomeState = HomeState()
}
