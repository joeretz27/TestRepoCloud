package com.rebahin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

class Rebahin : MainAPI() {

    override var mainUrl = "https://rebahinxxi3.mom"
    private val mainUrlJson = "https://raw.githubusercontent.com/joeretz27/TestRepoCloud/builds/Website.json"
    private val fallbackUrlJson = "https://raw.githubusercontent.com/Asm0d3usX/CloudX-V2/builds/Website.json"
    private var directUrl: String? = null
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "movies/page/%d/" to "Movies",
        "series/page/%d/" to "TV Series",
        "genre/action/page/%d/" to "Action",
        "genre/adventure/page/%d/" to "Adventure",
        "genre/comedy/page/%d/" to "Comedy",
        "genre/drama/page/%d/" to "Drama",
        "genre/horror/page/%d/" to "Horror",
        "genre/romance/page/%d/" to "Romance",
        "genre/science-fiction/page/%d/" to "Sci-Fi",
        "genre/drama-korea/page/%d/" to "Drama Korea",
        "genre/drama-jepang/page/%d/" to "Drama Jepang",
        "genre/drama-china/page/%d/" to "Drama China",
        "genre/thailand-series/page/%d/" to "Drama Thailand",
        "genre/series-indonesia/page/%d/" to "Series Indonesia",
        "genre/westseries/page/%d/" to "West Series"
    )

    private suspend fun loadMainUrlIfNeeded() {
        if (directUrl != null) return
        val urlsToTry = listOf(mainUrlJson, fallbackUrlJson)
        for (url in urlsToTry) {
            try {
                val response = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"), timeout = 60L).text
                val json = JSONObject(response)
                val array = json.optJSONArray("rebahin")
                val newUrl = array?.optString(0)?.removeSuffix("/")

                if (!newUrl.isNullOrBlank()) {
                    mainUrl = newUrl
                    directUrl = newUrl
                    return
                }
            } catch (_: Exception) { }
        }
        directUrl = mainUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            loadMainUrlIfNeeded()
            val document = app.get("$mainUrl/${request.data.format(page)}", headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), timeout = 60L).document
            val items = document.select("div.ml-item").mapNotNull { it.toSearchResult() }
            newHomePageResponse(request.name, items)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a.ml-mask") ?: selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst("span.mli-info h2")?.text()?.trim()
            ?: return null

        val img = selectFirst("img.mli-thumb, img.lazy, img")
        val posterUrl = fixUrlNull(
            img?.attr("data-original").takeIf { !it.isNullOrBlank() }
                ?: img?.attr("data-src").takeIf { !it.isNullOrBlank() }
                ?: img?.attr("src").takeIf { !it.isNullOrBlank() && !it.startsWith("data:") }
        )

        val quality = selectFirst("span.mli-quality")?.text()?.trim().orEmpty()
        val ratingText = selectFirst("span.mli-rating")?.text()?.trim()
        val epsText = selectFirst("div.mli-eps")?.text()?.trim()
        val eps = Regex("(\\d+)").find(epsText ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        val isSeries = href.contains("/series/") || eps != null

        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                if (eps != null) addSub(eps)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (quality.isNotEmpty()) addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            loadMainUrlIfNeeded()
            val document = app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), timeout = 60L).document
            document.select("div.ml-item").mapNotNull { it.toSearchResult() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        loadMainUrlIfNeeded()
        val fetch = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), timeout = 60L)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document

        val title = document.selectFirst("h3[itemprop=name]")?.ownText()?.trim()
            ?: document.selectFirst("h3[itemprop=name]")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.title().substringBefore("|").substringBefore("-").trim()

        val posterBg = document.selectFirst("div.mvic-thumb")?.attr("style")
        val posterUrlFromBg = Regex("url\\((.*?)\\)").find(posterBg ?: "")?.groupValues?.getOrNull(1)?.trim('\'', '"')
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: posterUrlFromBg
                ?: document.selectFirst("div.mvic-thumb img")?.attr("src")
        )

        val tags = document.select("div.mvici-left p:contains(Genre:) a, div.mvici-left span[itemprop=genre]").map { it.text().trim() }
        val actors = document.select("div.mvici-left span[itemprop=actor] a").map { it.text().trim() }
        val year = document.selectFirst("meta[itemprop=datePublished]")?.attr("content")?.take(4)?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val isSeries = url.contains("/series/") || document.selectFirst("a[href*=/watch]") != null
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        val description = document.selectFirst("div[itemprop=description] p")?.text()?.trim()
            ?: document.selectFirst("div[itemprop=description]")?.text()?.trim()

        val trailer = document.selectFirst("iframe#iframe-trailer, div.modal-body-trailer iframe")?.attr("src")?.let { fixUrl(it) }
        val rating = document.selectFirst("span.irank-voters, span.rating, div.imdb-rating span.rating")?.text()?.trim()
            ?: document.selectFirst("span.mli-rating")?.text()?.trim()

        val duration = document.selectFirst("div.mvici-right p:contains(Duration:)")?.text()
            ?.replace(Regex("\\D"), "")?.toIntOrNull()

        val recommendations = document.select("div.ml-item").mapNotNull { it.toSearchResult() }

        if (tvType == TvType.TvSeries) {
            val watchUrl = if (url.endsWith("/watch")) url else url.removeSuffix("/") + "/watch"
            val watchDoc = app.get(watchUrl).document

            val episodeMap = linkedMapOf<Int, MutableList<String>>()

            watchDoc.select("a.btn-eps").forEach { btn ->
                val epText = btn.text().trim()
                val epNum = Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val iframeB64 = btn.attr("data-iframe")
                val decoded = decodeIframeUrl(iframeB64)
                if (!decoded.isNullOrBlank()) {
                    episodeMap.getOrPut(epNum) { mutableListOf() }.add(decoded)
                }
            }

            val episodes = episodeMap.map { (epNum, serverList) ->
                val jsonArr = JSONArray()
                serverList.forEach { jsonArr.put(it) }
                newEpisode(jsonArr.toString()) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageReferer = "$mainUrl/"
        var anyEmitted = false
        Log.d("Rebahin", "loadLinks data=${data.take(120)}")
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            anyEmitted = true
            callback(link)
        }

        if (data.startsWith("[") || data.startsWith("{")) {
            try {
                val jsonArr = JSONArray(data)
                Log.d("Rebahin", "json array len=${jsonArr.length()}")
                for (i in 0 until jsonArr.length()) {
                    val serverUrl = jsonArr.optString(i)
                    if (!serverUrl.isNullOrBlank()) {
                        handleServerUrl(serverUrl, pageReferer, subtitleCallback, wrappedCallback)
                    }
                }
                return true
            } catch (_: Exception) { }
        }

        val playUrl = if (data.endsWith("/play")) data else data.removeSuffix("/") + "/play"
        val playDoc = try { app.get(playUrl, headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), referer = data, timeout = 60L).document } catch (_: Exception) { null }
        Log.d("Rebahin", "playDoc=${playDoc != null} playUrl=$playUrl")

        playDoc?.select("#server-list div.server, div.server-wrapper div.server, [data-iframe]")?.amap { server ->
            val b64 = server.attr("data-iframe")
            val decoded = decodeIframeUrl(b64)
            if (!decoded.isNullOrBlank()) {
                handleServerUrl(decoded, pageReferer, subtitleCallback, wrappedCallback)
            }
        }

        playDoc?.select("iframe#iframe-embed, div.content-embed iframe")?.forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            val url = httpsify(src)
            if (url.contains("abyssplayer", true)) return@forEach
            if (url.contains("/embed/", true)) {
                handleJuicyEmbed(url, pageReferer, subtitleCallback, wrappedCallback)
            } else {
                loadExtractor(url, pageReferer, subtitleCallback, wrappedCallback)
            }
        }

        if (!anyEmitted) {
            try {
                loadExtractor(data, pageReferer, subtitleCallback, wrappedCallback)
            } catch (_: Exception) { }
        }
        if (!anyEmitted) {
            try {
                wrappedCallback(newExtractorLink(name, name, data))
            } catch (_: Exception) { }
        }

        return true
    }

    private fun isJuicyEmbed(url: String): Boolean =
        url.contains("/embed/", true) && !url.contains("abyssplayer", true)

    private suspend fun handleServerUrl(
        url: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("abyssplayer", true) -> { /* skip */ }
            isJuicyEmbed(url) -> handleJuicyEmbed(url, pageReferer, subtitleCallback, callback)
            else -> loadExtractor(url, pageReferer, subtitleCallback, callback)
        }
    }

    private suspend fun handleJuicyEmbed(
        embedUrl: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var emitted = false
        Log.d("Rebahin", "handleJuicyEmbed start $embedUrl")
        try {
        val embedHost = getBaseUrl(embedUrl)
        val ua = USER_AGENT
        val fetchHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to pageReferer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US",
        )

        val html = try {
            app.get(embedUrl, headers = fetchHeaders, referer = pageReferer).text
        } catch (_: Exception) {
            null
        } ?: throw Exception("fetch")

        val juicyData = extractJuicyData(html) ?: throw Exception("juicy")
        val data = juicyData.optJSONObject("data") ?: throw Exception("data")
        val token = data.optString("token")
        val pingPathRaw = data.optJSONObject("routes")?.optString("ping")

        val config = decodeJuicyCodes(html) ?: throw Exception("decode")
        val sources = config.optJSONObject("sources")
        val m3u8Url = sources?.optString("file")
        if (m3u8Url.isNullOrBlank()) throw Exception("m3u8")

        // subtitles
        val tracks = config.optJSONArray("tracks")
        if (tracks != null) {
            for (k in 0 until tracks.length()) {
                val t = tracks.optJSONObject(k) ?: continue
                val kind = t.optString("kind")
                if (kind.equals("thumbnails", true)) continue
                val file = t.optString("file")
                if (file.isBlank()) continue
                val label = t.optString("label").ifBlank {
                    if (kind.equals("captions", true)) "Subtitle" else kind.ifBlank { "Subtitle" }
                }
                subtitleCallback(SubtitleFile(label, file))
            }
        }


        val streamHeaders = mapOf(
            "Referer" to embedUrl,
            "Origin" to embedHost,
            "User-Agent" to ua,
        )

        emitted = false
        try {
            val links = generateM3u8(name, m3u8Url, referer = embedUrl, headers = streamHeaders)
            if (links.isNotEmpty()) {
                links.forEach(callback)
                emitted = true
            }
        } catch (_: Exception) { }

        if (!emitted) {
            try {
                callback(
                    newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                        this.headers = streamHeaders
                    }
                )
                emitted = true
            } catch (_: Exception) { }
        }
        } catch (_: Exception) { }
        if (!emitted) {
            Log.d("Rebahin", "handleJuicyEmbed fallback loadExtractor $embedUrl")
            try {
                loadExtractor(embedUrl, pageReferer, subtitleCallback, callback)
                emitted = true
            } catch (_: Exception) { }
        }
        if (!emitted) {
            Log.d("Rebahin", "handleJuicyEmbed fallback emit embed $embedUrl")
            try {
                callback(newExtractorLink(name, name, embedUrl))
                emitted = true
            } catch (_: Exception) { }
        }
        Log.d("Rebahin", "handleJuicyEmbed end emitted=$emitted")
    }

    private fun extractJuicyData(html: String): JSONObject? {
        val idx = html.indexOf("juicyData")
        if (idx < 0) return null
        val braceStart = html.indexOf('{', idx)
        if (braceStart < 0) return null
        var depth = 0
        var i = braceStart
        while (i < html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val json = html.substring(braceStart, i + 1)
                        return try { JSONObject(json) } catch (_: Exception) { null }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun decodeJuicyCodes(html: String): JSONObject? {
        val regex = Regex("_juicycodes\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html) ?: return null
        val argStr = match.groupValues[1]
        val stringRegex = Regex("\"([^\"]*)\"")
        val arg = stringRegex.findAll(argStr).map { it.groupValues[1] }.joinToString("")
        if (arg.length < 4) return null

        val saltStr = arg.takeLast(3).map { (it.code - 100).toString() }.joinToString("")
        val salt = saltStr.toIntOrNull() ?: return null
        val body = arg.dropLast(3)

        var b64 = body.replace('_', '+').replace('-', '/')
        while (b64.length % 4 != 0) b64 += "="
        val decodedBytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        val decoded = String(decodedBytes, Charsets.UTF_8)

        val symbols = "`%-+*$!_^="
        val digits = StringBuilder()
        for (c in decoded) {
            val idx = symbols.indexOf(c)
            if (idx < 0) return null
            digits.append(idx)
        }
        val d = digits.toString()
        val out = StringBuilder()
        var i = 0
        while (i + 4 <= d.length) {
            val chunk = d.substring(i, i + 4)
            val code = chunk.toIntOrNull()?.mod(1000)?.minus(salt) ?: return null
            out.append(code.toChar())
            i += 4
        }
        val js = out.toString()
        val start = js.indexOf('{')
        val end = js.lastIndexOf('}')
        if (start < 0 || end < start) return null
        val jsonStr = js.substring(start, end + 1)
        return try { JSONObject(jsonStr) } catch (_: Exception) { null }
    }

    private fun decodeIframeUrl(b64: String?): String? {
        if (b64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            try {
                base64Decode(b64).trim()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getBaseUrl(url: String): String =
        URI(url).let { "${it.scheme}://${it.host}" }
}
