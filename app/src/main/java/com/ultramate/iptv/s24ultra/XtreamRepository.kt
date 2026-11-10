package com.ultramate.iptv.s24ultra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

class XtreamRepository {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun authenticateAndFetchServerInfo(playlist: XtreamPlaylist): Result<XtreamPlaylist> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(playlist.buildPlayerApiUrl()).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                    val body = resp.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val userInfo = json.optJSONObject("user_info")
                    val status = userInfo?.optString("status", "Active") ?: "Active"
                    val activeCons = userInfo?.optString("active_cons", "0")?.toIntOrNull() ?: 0
                    val maxCons = userInfo?.optString("max_connections", "1")?.toIntOrNull() ?: 1
                    Result.success(
                        playlist.copy(
                            status = status,
                            activeConnections = activeCons,
                            maxConnections = maxCons
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun fetchLiveStreams(playlist: XtreamPlaylist): List<LiveChannel> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<LiveChannel>()
            try {
                val req = Request.Builder().url(playlist.buildPlayerApiUrl("get_live_streams")).build()
                httpClient.newCall(req).execute().use { resp ->
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val streamId = obj.optInt("stream_id", i + 1)
                        val name = obj.optString("name", "Channel $streamId")
                        val catId = obj.optString("category_id", "general")
                        val tvArchive = obj.optInt("tv_archive", 0) == 1
                        result.add(
                            LiveChannel(
                                streamId = streamId,
                                num = obj.optInt("num", i + 1),
                                name = name,
                                categoryId = catId,
                                categoryName = "Live TV",
                                playlistId = playlist.id,
                                playlistName = playlist.name,
                                logoText = name.take(3).uppercase(),
                                hasCatchup = tvArchive
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
            result
        }

    suspend fun fetchXmltvEpg(playlist: XtreamPlaylist): Map<String, List<EpgProgram>> =
        withContext(Dispatchers.IO) {
            val map = mutableMapOf<String, MutableList<EpgProgram>>()
            try {
                val req = Request.Builder().url(playlist.buildXmltvEpgUrl()).build()
                httpClient.newCall(req).execute().use { resp ->
                    val xml = resp.body?.string() ?: return@withContext emptyMap()
                    val factory = XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(StringReader(xml))
                    var eventType = parser.eventType
                    var currentChannelId = ""
                    var title = ""
                    var desc = ""
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "programme" -> {
                                    currentChannelId = parser.getAttributeValue(null, "channel") ?: ""
                                    title = ""
                                    desc = ""
                                }
                                "title" -> title = parser.nextText()
                                "desc" -> desc = parser.nextText()
                            }
                        } else if (eventType == XmlPullParser.END_TAG && parser.name == "programme") {
                            if (currentChannelId.isNotEmpty() && title.isNotEmpty()) {
                                val list = map.getOrPut(currentChannelId) { mutableListOf() }
                                list.add(
                                    EpgProgram(
                                        id = "${currentChannelId}_${list.size}",
                                        title = title,
                                        description = desc,
                                        category = "Broadcast",
                                        startEpochMinutes = 0,
                                        endEpochMinutes = 60
                                    )
                                )
                            }
                        }
                        eventType = parser.next()
                    }
                }
            } catch (_: Exception) {
            }
            map
        }
}
