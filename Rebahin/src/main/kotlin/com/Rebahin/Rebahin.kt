package com.rebahin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
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
                val response = app.get(url).text
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
        loadMainUrlIfNeeded()
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("div.ml-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
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
        loadMainUrlIfNeeded()
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        loadMainUrlIfNeeded()
        val fetch = app.get(url)
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
        if (data.startsWith("[") || data.startsWith("{")) {
            try {
                val jsonArr = JSONArray(data)
                for (i in 0 until jsonArr.length()) {
                    val serverUrl = jsonArr.optString(i)
                    if (!serverUrl.isNullOrBlank()) {
                        loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)
                    }
                }
                return true
            } catch (_: Exception) { }
        }

        val playUrl = if (data.endsWith("/play")) data else data.removeSuffix("/") + "/play"
        val playDoc = app.get(playUrl).document

        val serverElements = playDoc.select("#server-list div.server, div.server-wrapper div.server, [data-iframe]")
        serverElements.amap { server ->
            val b64 = server.attr("data-iframe")
            val decoded = decodeIframeUrl(b64)
            if (!decoded.isNullOrBlank()) {
                loadExtractor(decoded, "$mainUrl/", subtitleCallback, callback)
            }
        }

        playDoc.select("iframe#iframe-embed, div.content-embed iframe").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            loadExtractor(httpsify(src), "$mainUrl/", subtitleCallback, callback)
        }

        return true
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
