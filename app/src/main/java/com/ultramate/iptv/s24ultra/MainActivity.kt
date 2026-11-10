@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.media3.common.util.UnstableApi::class
)

package com.ultramate.iptv.s24ultra

import android.app.PictureInPictureParams
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    secondary = Color(0xFF818CF8),
                    background = Color(0xFF000000),
                    surface = Color(0xFF0B0F17)
                )
            ) {
                UltraMateS24App(
                    onEnterPip = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val params = PictureInPictureParams.Builder()
                                .setAspectRatio(Rational(16, 9))
                                .build()
                            enterPictureInPictureMode(params)
                        }
                    }
                )
            }
        }
    }
}

object XtreamLiveClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "http://$trimmed"
        } else {
            trimmed
        }
    }

    private fun decodeBase64Safe(input: String): String {
        if (input.isBlank()) return ""
        return try {
            val decoded = Base64.decode(input, Base64.DEFAULT)
            String(decoded, Charsets.UTF_8).takeIf { it.isNotBlank() } ?: input
        } catch (_: Exception) {
            input
        }
    }

    suspend fun syncPlaylistData(playlist: XtreamPlaylist): Triple<XtreamPlaylist, List<LiveCategory>, List<LiveChannel>> =
        withContext(Dispatchers.IO) {
            val cleanUrl = normalizeServerUrl(playlist.serverUrl)
            val normPlaylist = playlist.copy(serverUrl = cleanUrl)
            val u = normPlaylist.username.trim()
            val p = normPlaylist.password.trim()

            // 1. Authenticate & fetch user_info
            var updatedPlaylist = normPlaylist
            val authUrl = "$cleanUrl/player_api.php?username=$u&password=$p"
            val authReq = Request.Builder()
                .url(authUrl)
                .header("User-Agent", "TiviMate/4.7.0 (Samsung SM-S928U; Android 14)")
                .build()

            httpClient.newCall(authReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("Server returned HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: "{}"
                val json = JSONObject(body)
                val userInfo = json.optJSONObject("user_info")
                if (userInfo != null) {
                    val authFlag = userInfo.optInt("auth", 1)
                    if (authFlag == 0) {
                        throw Exception("Invalid Xtream username or password")
                    }
                    val status = userInfo.optString("status", "Active")
                    val activeCons = userInfo.optString("active_cons", "0").toIntOrNull() ?: 0
                    val maxCons = userInfo.optString("max_connections", "1").toIntOrNull() ?: 1
                    val expRaw = userInfo.optString("exp_date", "")
                    val expFormatted = expRaw.toLongOrNull()?.let { sec ->
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sec * 1000L))
                    } ?: "Unlimited"

                    updatedPlaylist = normPlaylist.copy(
                        status = status,
                        activeConnections = activeCons,
                        maxConnections = maxCons,
                        expDate = expFormatted
                    )
                }
            }

            // 2. Fetch Live Categories
            val categories = mutableListOf<LiveCategory>()
            val catMap = mutableMapOf<String, String>()
            try {
                val catUrl = "$cleanUrl/player_api.php?username=$u&password=$p&action=get_live_categories"
                val catReq = Request.Builder()
                    .url(catUrl)
                    .header("User-Agent", "TiviMate/4.7.0 (Samsung SM-S928U; Android 14)")
                    .build()
                httpClient.newCall(catReq).execute().use { resp ->
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val cid = obj.optString("category_id", "")
                        val cname = obj.optString("category_name", "General")
                        if (cid.isNotEmpty()) {
                            catMap[cid] = cname
                            categories.add(LiveCategory(cid, cname, updatedPlaylist.id))
                        }
                    }
                }
            } catch (_: Exception) {
            }

            // 3. Fetch Live Streams
            val channels = mutableListOf<LiveChannel>()
            val streamsUrl = "$cleanUrl/player_api.php?username=$u&password=$p&action=get_live_streams"
            val streamReq = Request.Builder()
                .url(streamsUrl)
                .header("User-Agent", "TiviMate/4.7.0 (Samsung SM-S928U; Android 14)")
                .build()
            httpClient.newCall(streamReq).execute().use { resp ->
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val limit = minOf(arr.length(), 1500)
                for (i in 0 until limit) {
                    val obj = arr.getJSONObject(i)
                    val streamId = obj.optInt("stream_id", i + 1)
                    val num = obj.optInt("num", i + 1)
                    val name = obj.optString("name", "Channel $num")
                    val catId = obj.optString("category_id", "general")
                    val catName = catMap[catId] ?: "Live TV"
                    val tvArchive = obj.optInt("tv_archive", 0) == 1
                    val streamUrl = "$cleanUrl/live/$u/$p/$streamId.${updatedPlaylist.streamFormat}"

                    channels.add(
                        LiveChannel(
                            streamId = streamId,
                            num = num,
                            name = name,
                            categoryId = catId,
                            categoryName = catName,
                            playlistId = updatedPlaylist.id,
                            playlistName = updatedPlaylist.name,
                            logoText = name.take(3).uppercase(),
                            resolution = "LIVE • ${updatedPlaylist.streamFormat.uppercase()}",
                            hasCatchup = tvArchive,
                            directStreamUrl = streamUrl
                        )
                    )
                }
            }

            Triple(updatedPlaylist, categories, channels)
        }

    suspend fun fetchChannelShortEpg(playlist: XtreamPlaylist, streamId: Int): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<EpgProgram>()
            try {
                val cleanUrl = normalizeServerUrl(playlist.serverUrl)
                val u = playlist.username.trim()
                val p = playlist.password.trim()
                val url = "$cleanUrl/player_api.php?username=$u&password=$p&action=get_short_epg&stream_id=$streamId&limit=6"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "TiviMate/4.7.0 (Samsung SM-S928U; Android 14)")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val arr = json.optJSONArray("epg_listings") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val rawTitle = item.optString("title", "")
                        val rawDesc = item.optString("description", "")
                        val startStr = item.optString("start", "")
                        val endStr = item.optString("end", "")
                        val title = decodeBase64Safe(rawTitle).ifBlank { "Live Program" }
                        val desc = decodeBase64Safe(rawDesc).ifBlank { "$startStr - $endStr" }
                        list.add(
                            EpgProgram(
                                id = "${streamId}_$i",
                                title = title,
                                description = "$startStr → $endStr • $desc",
                                category = "TV Guide",
                                startEpochMinutes = i * 60,
                                endEpochMinutes = (i + 1) * 60
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
            list
        }
}

object PlaylistStorage {
    private const val PREF_NAME = "ultramate_s24_prefs"
    private const val KEY_PLAYLISTS = "saved_playlists_json"

    fun savePlaylists(context: Context, playlists: List<XtreamPlaylist>) {
        val arr = JSONArray()
        for (pl in playlists) {
            val obj = JSONObject().apply {
                put("id", pl.id)
                put("name", pl.name)
                put("serverUrl", pl.serverUrl)
                put("username", pl.username)
                put("password", pl.password)
                put("enabled", pl.enabled)
                put("streamFormat", pl.streamFormat)
                put("epgOffsetHours", pl.epgOffsetHours.toDouble())
                put("status", pl.status)
                put("activeConnections", pl.activeConnections)
                put("maxConnections", pl.maxConnections)
                put("expDate", pl.expDate)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYLISTS, arr.toString())
            .apply()
    }

    fun loadPlaylists(context: Context): List<XtreamPlaylist>? {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLAYLISTS, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<XtreamPlaylist>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    XtreamPlaylist(
                        id = obj.optString("id", "pl_${System.currentTimeMillis()}_$i"),
                        name = obj.optString("name", "Playlist ${i + 1}"),
                        serverUrl = obj.optString("serverUrl", ""),
                        username = obj.optString("username", ""),
                        password = obj.optString("password", ""),
                        enabled = obj.optBoolean("enabled", true),
                        streamFormat = obj.optString("streamFormat", "m3u8"),
                        epgOffsetHours = obj.optDouble("epgOffsetHours", 0.0).toFloat(),
                        status = obj.optString("status", "Ready"),
                        activeConnections = obj.optInt("activeConnections", 0),
                        maxConnections = obj.optInt("maxConnections", 2),
                        expDate = obj.optString("expDate", "Active")
                    )
                )
            }
            list
        } catch (_: Exception) {
            null
        }
    }
}

fun defaultSampleChannels(): List<LiveChannel> = listOf(
    LiveChannel(
        streamId = 101,
        num = 101,
        name = "Demo Sports UHD (Tap Playlists to Add Yours)",
        categoryId = "demo_sports",
        categoryName = "Sports",
        playlistId = "pl_1",
        playlistName = "Demo Playlist 1",
        logoText = "SPT",
        resolution = "FHD 60fps",
        hasCatchup = true,
        directStreamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        isFavorite = true,
        epgPrograms = listOf(
            EpgProgram("1", "Championship Live Broadcast", "Tap the Playlists tab below to add your own Xtream Codes playlist!", "Sports", 0, 60),
            EpgProgram("2", "Post-Game Highlights & Analysis", "Built-in Xtream EPG syncs automatically when you add your server.", "Sports", 60, 120)
        )
    ),
    LiveChannel(
        streamId = 102,
        num = 102,
        name = "Demo Cinema 4K Stream",
        categoryId = "demo_movies",
        categoryName = "Movies",
        playlistId = "pl_1",
        playlistName = "Demo Playlist 1",
        logoText = "MOV",
        resolution = "4K HEVC",
        hasCatchup = true,
        directStreamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        isFavorite = false,
        epgPrograms = listOf(
            EpgProgram("3", "Feature Presentation: Sci-Fi Showcase", "Adaptive HLS stream running on S24 Ultra Media3 ExoPlayer.", "Movies", 0, 90)
        )
    ),
    LiveChannel(
        streamId = 201,
        num = 201,
        name = "Demo World News Live",
        categoryId = "demo_news",
        categoryName = "News",
        playlistId = "pl_2",
        playlistName = "Demo Playlist 2",
        logoText = "NWS",
        resolution = "FHD 60fps",
        hasCatchup = true,
        directStreamUrl = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8",
        isFavorite = true,
        epgPrograms = listOf(
            EpgProgram("4", "Global Headlines Live", "Multi-playlist switching enabled.", "News", 0, 60)
        )
    )
)

@Composable
fun UltraMateS24App(onEnterPip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf("live") }
    var settings by remember { mutableStateOf(PlayerSettings()) }

    var playlists by remember {
        mutableStateOf(
            PlaylistStorage.loadPlaylists(context) ?: listOf(
                XtreamPlaylist(
                    id = "pl_1",
                    name = "Demo Playlist 1 (Tap Edit or Delete)",
                    serverUrl = "http://line.your-xtream-server.com:8080",
                    username = "your_username",
                    password = "your_password",
                    enabled = true,
                    streamFormat = "m3u8"
                ),
                XtreamPlaylist(
                    id = "pl_2",
                    name = "Demo Playlist 2 (Tap Edit or Delete)",
                    serverUrl = "http://backup.your-xtream-server.com:8080",
                    username = "your_username_2",
                    password = "your_password_2",
                    enabled = true,
                    streamFormat = "m3u8"
                )
            )
        )
    }

    var allChannels by remember { mutableStateOf(defaultSampleChannels()) }
    var currentChannel by remember { mutableStateOf(allChannels.first()) }
    var recentChannels by remember { mutableStateOf(listOf(allChannels.first())) }
    var selectedPlaylistFilter by remember { mutableStateOf("ALL") }
    var selectedCategoryFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusMessage by remember { mutableStateOf("Tap '+ Add Playlist' to load your Xtream Codes channels & EPG") }

    // Add / Edit Playlist Dialog state
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var editingPlaylist by remember { mutableStateOf<XtreamPlaylist?>(null) }

    // Player states
    var brightnessLevel by remember { mutableStateOf(0.9f) }
    var volumeLevel by remember { mutableStateOf(0.85f) }
    var isMuted by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("TiviMate/4.7.0 (Samsung SM-S928U; Android 14)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 8000, 1000, 2000)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(MediaItem.fromUri(currentChannel.directStreamUrl ?: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"))
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // Auto-fallback between .m3u8 and .ts if Xtream stream format differs
                        val curUrl = currentChannel.directStreamUrl ?: return
                        if (curUrl.endsWith(".m3u8")) {
                            val tsUrl = curUrl.removeSuffix(".m3u8") + ".ts"
                            setMediaItem(MediaItem.fromUri(tsUrl))
                            prepare()
                            play()
                        }
                    }
                })
            }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    fun tuneToChannel(ch: LiveChannel) {
        currentChannel = ch
        recentChannels = (listOf(ch) + recentChannels.filter { it.streamId != ch.streamId }).take(10)
        val url = ch.directStreamUrl ?: return
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        isPlaying = true

        // Fetch live short EPG in background if not yet populated
        val pl = playlists.find { it.id == ch.playlistId }
        if (pl != null && ch.epgPrograms.isEmpty()) {
            scope.launch {
                val epg = XtreamLiveClient.fetchShortEpgForChannel(pl, ch.streamId)
                if (epg.isNotEmpty()) {
                    allChannels = allChannels.map {
                        if (it.streamId == ch.streamId && it.playlistId == ch.playlistId) it.copy(epgPrograms = epg) else it
                    }
                    if (currentChannel.streamId == ch.streamId) {
                        currentChannel = currentChannel.copy(epgPrograms = epg)
                    }
                }
            }
        }
    }

    fun syncXtreamPlaylist(pl: XtreamPlaylist) {
        scope.launch {
            isSyncing = true
            syncStatusMessage = "Connecting to ${pl.name} (${pl.serverUrl})..."
            try {
                val (updatedPl, _, fetchedChannels) = XtreamLiveClient.syncPlaylistData(pl)
                val updatedList = if (playlists.any { it.id == updatedPl.id }) {
                    playlists.map { if (it.id == updatedPl.id) updatedPl else it }
                } else {
                    playlists + updatedPl
                }
                playlists = updatedList
                PlaylistStorage.savePlaylists(context, updatedList)

                // Replace channels for this playlist while keeping other playlists' channels
                val otherChannels = allChannels.filter { it.playlistId != updatedPl.id && !it.categoryId.startsWith("demo_") }
                allChannels = otherChannels + fetchedChannels
                if (fetchedChannels.isNotEmpty()) {
                    tuneToChannel(fetchedChannels.first())
                }
                syncStatusMessage = "Loaded ${fetchedChannels.size} channels + EPG from ${updatedPl.name}"
                Toast.makeText(context, "Loaded ${fetchedChannels.size} channels from ${updatedPl.name}!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                syncStatusMessage = "Sync notice (${pl.name}): ${e.message ?: "Check URL/credentials"}"
                Toast.makeText(context, "Saved playlist (${e.message ?: "Verify URL/login"})", Toast.LENGTH_LONG).show()
            } finally {
                isSyncing = false
            }
        }
    }

    val enabledPlaylistIds = playlists.filter { it.enabled }.map { it.id }.toSet()
    val filteredChannels = remember(allChannels, enabledPlaylistIds, selectedPlaylistFilter, selectedCategoryFilter, searchQuery) {
        allChannels.filter { ch ->
            val inEnabled = ch.playlistId in enabledPlaylistIds
            val matchPl = selectedPlaylistFilter == "ALL" || ch.playlistId == selectedPlaylistFilter
            val matchCat = when (selectedCategoryFilter) {
                "ALL" -> true
                "FAV" -> ch.isFavorite
                else -> ch.categoryName.equals(selectedCategoryFilter, ignoreCase = true)
            }
            val matchSearch = searchQuery.isBlank() ||
                ch.name.contains(searchQuery, ignoreCase = true) ||
                ch.num.toString().contains(searchQuery)
            inEnabled && matchPl && matchCat && matchSearch
        }
    }

    val availableCategories = remember(allChannels, enabledPlaylistIds, selectedPlaylistFilter) {
        val base = allChannels.filter {
            it.playlistId in enabledPlaylistIds && (selectedPlaylistFilter == "ALL" || it.playlistId == selectedPlaylistFilter)
        }.map { it.categoryName }.distinct()
        listOf("ALL", "FAV") + base
    }

    Scaffold(
        containerColor = if (settings.amoledPureBlack) Color.Black else Color(0xFF0B0F17),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B0F17)) {
                NavigationBarItem(
                    selected = activeTab == "live",
                    onClick = { activeTab = "live" },
                    icon = { Icon(Icons.Default.LiveTv, contentDescription = "Live TV") },
                    label = { Text("Live TV", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = activeTab == "epg",
                    onClick = { activeTab = "epg" },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = "EPG Guide") },
                    label = { Text("EPG Guide", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = activeTab == "playlists",
                    onClick = { activeTab = "playlists" },
                    icon = { Icon(Icons.Default.Storage, contentDescription = "Playlists") },
                    label = { Text("Playlists (${playlists.size})", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = activeTab == "settings",
                    onClick = { activeTab = "settings" },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings", fontSize = 11.sp) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top S24 Ultra Action Header with Quick "+ Add Playlist" Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0F17))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ULTRAMATE IPTV • S24 ULTRA",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${currentChannel.num} • ${currentChannel.name}",
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = {
                        editingPlaylist = null
                        showPlaylistDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Playlist", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Video Player Box with Touch Gestures
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black)
                    .pointerInput(settings.swipeGestureBrightnessVolume) {
                        if (!settings.swipeGestureBrightnessVolume) return@pointerInput
                        detectVerticalDragGestures { change, dragAmount ->
                            val isLeftHalf = change.position.x < size.width / 2f
                            val delta = -dragAmount / 500f
                            if (isLeftHalf) {
                                brightnessLevel = (brightnessLevel + delta).coerceIn(0.15f, 1.0f)
                            } else {
                                volumeLevel = (volumeLevel + delta).coerceIn(0.0f, 1.0f)
                                exoPlayer.volume = volumeLevel
                            }
                        }
                    }
                    .pointerInput(settings.doubleTapPlayPause) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (settings.doubleTapPlayPause) {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    isPlaying = exoPlayer.isPlaying
                                }
                            }
                        )
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            this.resizeMode = resizeMode
                        }
                    },
                    update = { view ->
                        view.resizeMode = resizeMode
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top-Right Quick Controls Overlay (Aspect Ratio & PiP)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }
                    ) {
                        Text(
                            text = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "19.5:9 Fill"
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                                else -> "16:9 Fit"
                            },
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = {
                        isMuted = !isMuted
                        exoPlayer.volume = if (isMuted) 0f else volumeLevel
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = "Mute",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onEnterPip, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.PictureInPictureAlt,
                            contentDescription = "PiP",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Recent Channels Strip (TiviMate Style)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF080C14))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Text("RECENT:", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                items(recentChannels) { rch ->
                    val selected = rch.streamId == currentChannel.streamId
                    Surface(
                        color = if (selected) Color(0xFF0284C7) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { tuneToChannel(rch) }
                    ) {
                        Text(
                            text = "${rch.num} ${rch.name}",
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Playlist Filter Chips (All Merged vs Individual Playlists)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedPlaylistFilter == "ALL",
                        onClick = { selectedPlaylistFilter = "ALL" },
                        label = { Text("All Playlists (${playlists.count { it.enabled }})", fontSize = 11.sp) }
                    )
                }
                items(playlists.filter { it.enabled }) { pl ->
                    FilterChip(
                        selected = selectedPlaylistFilter == pl.id,
                        onClick = { selectedPlaylistFilter = pl.id },
                        label = { Text(pl.name, fontSize = 11.sp) }
                    )
                }
            }

            if (isSyncing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF38BDF8)
                )
            }

            // Main Content Views
            when (activeTab) {
                "live" -> LiveTvScreen(
                    channels = filteredChannels,
                    currentChannel = currentChannel,
                    categories = availableCategories,
                    selectedCategory = selectedCategoryFilter,
                    onSelectCategory = { selectedCategoryFilter = it },
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    onSelectChannel = { tuneToChannel(it) },
                    onToggleFavorite = { target ->
                        allChannels = allChannels.map {
                            if (it.streamId == target.streamId && it.playlistId == target.playlistId) {
                                it.copy(isFavorite = !it.isFavorite)
                            } else it
                        }
                    }
                )
                "epg" -> EpgGuideScreen(
                    channels = filteredChannels,
                    currentChannel = currentChannel,
                    syncStatusMessage = syncStatusMessage,
                    onSelectChannel = { tuneToChannel(it) },
                    onRefreshEpg = {
                        playlists.filter { it.enabled }.forEach { pl -> syncXtreamPlaylist(pl) }
                    }
                )
                "playlists" -> PlaylistsManagerScreen(
                    playlists = playlists,
                    syncStatusMessage = syncStatusMessage,
                    onAddPlaylistClick = {
                        editingPlaylist = null
                        showPlaylistDialog = true
                    },
                    onEditPlaylistClick = { pl ->
                        editingPlaylist = pl
                        showPlaylistDialog = true
                    },
                    onSyncPlaylistClick = { pl ->
                        syncXtreamPlaylist(pl)
                    },
                    onTogglePlaylist = { pl, checked ->
                        val updated = playlists.map { if (it.id == pl.id) it.copy(enabled = checked) else it }
                        playlists = updated
                        PlaylistStorage.savePlaylists(context, updated)
                    },
                    onDeletePlaylist = { pl ->
                        val updated = playlists.filter { it.id != pl.id }
                        playlists = updated
                        PlaylistStorage.savePlaylists(context, updated)
                        allChannels = allChannels.filter { it.playlistId != pl.id }
                    }
                )
                "settings" -> MobileSettingsScreen(
                    settings = settings,
                    onUpdateSettings = { settings = it },
                    onClearDemoPlaylists = {
                        val customOnly = playlists.filter { !it.name.startsWith("Demo ") }
                        playlists = customOnly
                        PlaylistStorage.savePlaylists(context, customOnly)
                        allChannels = allChannels.filter { !it.categoryId.startsWith("demo_") }
                        Toast.makeText(context, "Demo playlists removed. Tap '+ Add Playlist' to add yours!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    if (showPlaylistDialog) {
        AddOrEditXtreamDialog(
            initial = editingPlaylist,
            nextIndex = playlists.size + 1,
            onDismiss = { showPlaylistDialog = false },
            onSaveAndSync = { savedPl ->
                showPlaylistDialog = false
                val exists = playlists.any { it.id == savedPl.id }
                val updated = if (exists) {
                    playlists.map { if (it.id == savedPl.id) savedPl else it }
                } else {
                    playlists + savedPl
                }
                playlists = updated
                PlaylistStorage.savePlaylists(context, updated)
                syncXtreamPlaylist(savedPl)
            }
        )
    }
}

@Composable
fun LiveTvScreen(
    channels: List<LiveChannel>,
    currentChannel: LiveChannel,
    categories: List<String>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSelectChannel: (LiveChannel) -> Unit,
    onToggleFavorite: (LiveChannel) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search channels or number...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        )

        // Category Horizontal Bar
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(categories) { cat ->
                val label = when (cat) {
                    "ALL" -> "All Channels (${channels.size})"
                    "FAV" -> "★ Favorites"
                    else -> cat
                }
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { onSelectCategory(cat) },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        // Channel Rows
        if (channels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No channels found. Tap 'Playlists' or '+ Add Playlist' to load your Xtream Codes provider.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(channels) { ch ->
                    val isSelected = ch.streamId == currentChannel.streamId && ch.playlistId == currentChannel.playlistId
                    val nowProgram = ch.epgPrograms.firstOrNull()?.title ?: "${ch.categoryName} • Live Stream"
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF0C2D48) else Color(0xFF111827)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectChannel(ch) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = ch.num.toString(),
                                        color = Color(0xFF38BDF8),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ch.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Now: $nowProgram",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${ch.playlistName} • ${ch.resolution}",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            IconButton(onClick = { onToggleFavorite(ch) }) {
                                Icon(
                                    if (ch.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = "Favorite",
                                    tint = if (ch.isFavorite) Color(0xFFFACC15) else Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpgGuideScreen(
    channels: List<LiveChannel>,
    currentChannel: LiveChannel,
    syncStatusMessage: String,
    onSelectChannel: (LiveChannel) -> Unit,
    onRefreshEpg: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Built-In Xtream Codes EPG Guide",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    syncStatusMessage,
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onRefreshEpg,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Sync EPG", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sync EPG", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(channels) { ch ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (ch.streamId == currentChannel.streamId) Color(0xFF0C2D48) else Color(0xFF111827)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectChannel(ch) }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${ch.num} • ${ch.name}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                if (ch.hasCatchup) "⟳ Catch-Up" else "LIVE",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp
                            )
                        }
                        if (ch.epgPrograms.isEmpty()) {
                            Text(
                                "Tap channel to load live Xtream EPG listings & watch stream",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        } else {
                            ch.epgPrograms.take(3).forEach { prog ->
                                Text(
                                    "• ${prog.title} (${prog.description})",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistsManagerScreen(
    playlists: List<XtreamPlaylist>,
    syncStatusMessage: String,
    onAddPlaylistClick: () -> Unit,
    onEditPlaylistClick: (XtreamPlaylist) -> Unit,
    onSyncPlaylistClick: (XtreamPlaylist) -> Unit,
    onTogglePlaylist: (XtreamPlaylist, Boolean) -> Unit,
    onDeletePlaylist: (XtreamPlaylist) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C2D48)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Xtream Codes Multi-Playlist Manager",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Add 1, 2, or more Xtream Codes playlists. Channels and built-in EPG sync automatically.",
                        color = Color(0xFFBAE6FD),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Text(
                        syncStatusMessage,
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Button(
                        onClick = onAddPlaylistClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "+ Add My Xtream Codes Playlist",
                            color = Color.Black,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        items(playlists) { pl ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Server: ${pl.serverUrl}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text("User: ${pl.username} • Format: ${pl.streamFormat.uppercase()}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text(
                                "Status: ${pl.status} • Connections: ${pl.activeConnections}/${pl.maxConnections} • Exp: ${pl.expDate}",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = pl.enabled,
                            onCheckedChange = { checked -> onTogglePlaylist(pl, checked) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSyncPlaylistClick(pl) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Load Channels", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { onEditPlaylistClick(pl) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit Login", fontSize = 11.sp)
                        }
                        IconButton(onClick = { onDeletePlaylist(pl) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF87171))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddOrEditXtreamDialog(
    initial: XtreamPlaylist?,
    nextIndex: Int,
    onDismiss: () -> Unit,
    onSaveAndSync: (XtreamPlaylist) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "My Xtream Playlist #$nextIndex") }
    var serverUrl by remember { mutableStateOf(initial?.serverUrl ?: "http://") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var streamFormat by remember { mutableStateOf(initial?.streamFormat ?: "m3u8") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        title = {
            Text(
                if (initial == null) "Add Xtream Codes Playlist" else "Edit Xtream Codes Playlist",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter your Xtream Codes Server URL, Username, and Password. Built-in EPG (xmltv.php) connects automatically.",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL (e.g. http://server.com:8080)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Xtream Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Xtream Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stream Format:", color = Color.White, fontSize = 12.sp)
                    FilterChip(
                        selected = streamFormat == "m3u8",
                        onClick = { streamFormat = "m3u8" },
                        label = { Text("HLS (.m3u8)") }
                    )
                    FilterChip(
                        selected = streamFormat == "ts",
                        onClick = { streamFormat = "ts" },
                        label = { Text("MPEG-TS (.ts)") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanServer = XtreamLiveClient.normalizeServerUrl(serverUrl)
                    val pl = XtreamPlaylist(
                        id = initial?.id ?: "pl_${System.currentTimeMillis()}",
                        name = name.ifBlank { "Xtream Playlist" },
                        serverUrl = cleanServer,
                        username = username.trim(),
                        password = password.trim(),
                        enabled = true,
                        streamFormat = streamFormat
                    )
                    onSaveAndSync(pl)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
            ) {
                Text("Save & Load Channels", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}

@Composable
fun MobileSettingsScreen(
    settings: PlayerSettings,
    onUpdateSettings: (PlayerSettings) -> Unit,
    onClearDemoPlaylists: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "Mobile IPTV Settings (Samsung S24 Ultra)",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("AMOLED Pure Black Mode (#000000)", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = settings.amoledPureBlack,
                    onCheckedChange = { onUpdateSettings(settings.copy(amoledPureBlack = it)) }
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Swipe Left/Right for Brightness & Volume", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = settings.swipeGestureBrightnessVolume,
                    onCheckedChange = { onUpdateSettings(settings.copy(swipeGestureBrightnessVolume = it)) }
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Double-Tap Video to Play/Pause", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = settings.doubleTapPlayPause,
                    onCheckedChange = { onUpdateSettings(settings.copy(doubleTapPlayPause = it)) }
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("HLS Low-Latency Mode", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = settings.lowLatencyHls,
                    onCheckedChange = { onUpdateSettings(settings.copy(lowLatencyHls = it)) }
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto-Sync Xtream EPG on Startup", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = settings.epgSyncOnStartup,
                    onCheckedChange = { onUpdateSettings(settings.copy(epgSyncOnStartup = it)) }
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("120Hz Adaptive Frame Rate Match", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = settings.autoFrameRate120Hz,
                    onCheckedChange = { onUpdateSettings(settings.copy(autoFrameRate120Hz = it)) }
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClearDemoPlaylists,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove Demo", tint = Color(0xFFF87171))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Remove Preloaded Demo Playlists", color = Color(0xFFF87171))
            }
        }
    }
}
