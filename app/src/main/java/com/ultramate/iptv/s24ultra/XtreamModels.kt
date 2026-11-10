package com.ultramate.iptv.s24ultra

data class XtreamPlaylist(
    val id: String,
    val name: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val enabled: Boolean = true,
    val streamFormat: String = "m3u8", // "m3u8" or "ts"
    val epgOffsetHours: Float = 0f,
    val status: String = "Active",
    val activeConnections: Int = 1,
    val maxConnections: Int = 2,
    val expDate: String = "Unlimited"
) {
    fun buildLiveStreamUrl(streamId: Int): String {
        val base = serverUrl.trimEnd('/')
        return "$base/live/$username/$password/$streamId.$streamFormat"
    }

    fun buildPlayerApiUrl(action: String? = null, extraParams: String = ""): String {
        val base = serverUrl.trimEnd('/')
        val act = if (action != null) "&action=$action" else ""
        return "$base/player_api.php?username=$username&password=$password$act$extraParams"
    }

    fun buildXmltvEpgUrl(): String {
        val base = serverUrl.trimEnd('/')
        return "$base/xmltv.php?username=$username&password=$password"
    }
}

data class LiveCategory(
    val categoryId: String,
    val categoryName: String,
    val playlistId: String
)

data class LiveChannel(
    val streamId: Int,
    val num: Int,
    val name: String,
    val categoryId: String,
    val categoryName: String,
    val playlistId: String,
    val playlistName: String,
    val logoText: String,
    val resolution: String = "FHD 60fps",
    val hasCatchup: Boolean = true,
    val directStreamUrl: String? = null,
    val isFavorite: Boolean = false,
    val isLocked: Boolean = false,
    val epgPrograms: List<EpgProgram> = emptyList()
)

data class EpgProgram(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val startEpochMinutes: Int,
    val endEpochMinutes: Int,
    val isCatchupAvailable: Boolean = true
)

data class PlayerSettings(
    val decoderMode: String = "Hardware (MediaCodec)",
    val bufferSizeSec: Int = 3,
    val lowLatencyHls: Boolean = true,
    val aspectRatioMode: String = "19.5:9 S24 Ultra Fit",
    val autoFrameRate120Hz: Boolean = true,
    val amoledPureBlack: Boolean = true,
    val swipeGestureBrightnessVolume: Boolean = true,
    val doubleTapPlayPause: Boolean = true,
    val autoPipOnBackground: Boolean = true,
    val epgUpdateIntervalHours: Int = 6,
    val epgSyncOnStartup: Boolean = true,
    val epgOffsetHours: Float = 0f,
    val parentalPinEnabled: Boolean = false,
    val parentalPin: String = "0000"
)
