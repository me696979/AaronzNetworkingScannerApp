package net.aaronznetworking.scanner

/**
 * Shared configuration for automatic National Weather Service takeovers.
 *
 * The server exposes TG 5500 as the NWS Weather Radio source.  During a
 * qualifying active weather alert the Android player should start from the
 * newest completed NWS chunk, remain in weather mode for TAKEOVER_MS, then
 * return to normal scanner playback.  While the alert remains active the
 * takeover may repeat every REPEAT_MS.
 */
object WeatherTakeoverConfig {
    const val NWS_TALKGROUP = "5500"
    const val LATEST_NWS_PATH =
        "/api/call-queue/latest-talkgroup?talkgroup=5500&max_age_seconds=300"

    const val TAKEOVER_MS = 90_000L
    const val REPEAT_MS = 300_000L
}
