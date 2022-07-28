package com.lagradost.cloudstream3.movieproviders

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.network.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import kotlin.system.measureTimeMillis
import com.lagradost.nicehttp.NiceResponse


class FmoviesAPPProvider : MainAPI() {
    override var mainUrl = "https://fmovies.app"
    override var name = "Fmovies"
    override var lang = "en"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val usesWebView = true
    override val supportedTypes = setOf(
        TvType.Mirror,
    )
    override val vpnStatus = VPNStatus.None

    override suspend fun getMainPage(): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val document = Jsoup.parse(html)

        val all = ArrayList<HomePageList>()

        val map = mapOf(
            "Trending Movies" to "div#trending-movies",
            "Trending TV Shows" to "div#trending-tv",
        )
        map.forEach {
            all.add(HomePageList(
                it.key,
                document.select(it.value).select("div.film-poster").map { element ->
                    element.toSearchResult()
                }
            ))
        }

        document.select("section.block_area.block_area_home.section-id-02").forEach {
            val title = it.select("h2.cat-heading").text().trim()
            val elements = it.select("div.film-poster").map { element ->
                element.toSearchResult()
            }
            all.add(HomePageList(title, elements))
        }

        return HomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "-")}"
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        return document.select("div.flw-item").map {
            val title = it.select("h2.film-name").text()
            val href = fixUrl(it.select("a").attr("href"))
            val year = it.select("span.fdi-item").text().toIntOrNull()
            val image = it.select("img").attr("data-src")
            val isMovie = href.contains("/movie/")

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    year
                )
            } else {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    year,
                    null
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val details = document.select("div.detail_page-watch")
        val img = document.select("img.film-poster-img")
        val posterUrl = img.attr("src")
        val title = img.attr("title")
        val year = Regex("""[Rr]eleased:\s*(\d{4})""").find(
            document.select("div.elements").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val duration = Regex("""[Dd]uration:\s*(\d*)""").find(
            document.select("div.elements").text()
        )?.groupValues?.get(1)?.trim()?.plus(" min")

        val plot = document.select("div.description").text()

        val isMovie = url.contains("/movie/")

        // https://sflix.to/movie/free-never-say-never-again-hd-18317 -> 18317
        val idRegex = Regex(""".*-(\d+)""")
        val dataId = details.attr("data-id")
        val id = if (dataId.isNullOrEmpty())
            idRegex.find(url)?.groupValues?.get(1)
                ?: throw RuntimeException("Unable to get id from '$url'")
        else dataId

        if (isMovie) {
            // Movies
            val episodesUrl = "$mainUrl/ajax/movie/episodes/$id"
            val episodes = app.get(episodesUrl).text

            // Supported streams, they're identical
            val sourceIds = Jsoup.parse(episodes).select(".link-item").mapNotNull { element ->
                val sourceId = element.attr("data-linkid") ?: return@mapNotNull null
                if (element.select("span")?.text()?.trim()?.isValidServer() == true) {
                    "$url.$sourceId".replace("/movie/", "/watch-movie/")
                } else {
                    null
                }
            }

            return newMovieLoadResponse(title, url, TvType.Movie, sourceIds.toJson()) {
                this.year = year
                this.posterUrl = posterUrl
                this.plot = plot
                addDuration(duration)
            }
        } else {
            val seasonsDocument = app.get("$mainUrl/ajax/v2/tv/seasons/$id").document
            val episodes = arrayListOf<Episode>()

            seasonsDocument.select(".dropdown-menu a")
                .forEachIndexed { season, element ->
                    val seasonId = element.attr("data-id")
                    if (seasonId.isNullOrBlank()) return@forEachIndexed

                    var episode = 0
                    app.get("$mainUrl/ajax/v2/season/episodes/$seasonId").document
                        .select(".nav-item a")
                        .forEach {
                            val episodeImg = null
                            val episodeTitle = it.attr("title") ?: return@forEach
                            val episodePosterUrl = null
                            val episodeData = it.attr("data-id") ?: return@forEach

                            episode++

                            val episodeNum =
                                (it.select("strong")?.text()
                                    ?: episodeTitle).let { str ->
                                    Regex("""\d+""").find(str)?.groupValues?.firstOrNull()
                                        ?.toIntOrNull()
                                } ?: episode

                            episodes.add(
                                newEpisode(Pair(url, episodeData)) {
                                    this.posterUrl = fixUrlNull(episodePosterUrl)
                                    this.name = episodeTitle?.removePrefix("Episode $episodeNum: ")
                                    this.season = season + 1
                                    this.episode = episodeNum
                                }
                            )
                        }
                }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                addDuration(duration)
            }
        }
    }

    data class Tracks(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class Sources(
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )

    data class SourceObject(
        @JsonProperty("sources") val sources: List<Sources?>?,
        @JsonProperty("sources_1") val sources1: List<Sources?>?,
        @JsonProperty("sources_2") val sources2: List<Sources?>?,
        @JsonProperty("sourcesBackup") val sourcesBackup: List<Sources?>?,
        @JsonProperty("tracks") val tracks: List<Tracks?>?
    )

    data class IframeJson(
//        @JsonProperty("type") val type: String? = null,
        @JsonProperty("link") val link: String? = null,
//        @JsonProperty("sources") val sources: ArrayList<String> = arrayListOf(),
//        @JsonProperty("tracks") val tracks: ArrayList<String> = arrayListOf(),
//        @JsonProperty("title") val title: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = (tryParseJson<Pair<String, String>>(data)?.let { (prefix, server) ->
            val episodesUrl = "$mainUrl/ajax/v2/episode/servers/$server"

            // Supported streams, they're identical
            app.get(episodesUrl).document.select("a").mapNotNull { element ->
                val id = element?.attr("data-id") ?: return@mapNotNull null
                if (element.select("span")?.text()?.trim()?.isValidServer() == true) {
                    "$prefix.$id".replace("/tv/", "/watch-tv/")
                } else {
                    null
                }
            }
        } ?: tryParseJson<List<String>>(data))?.distinct()

        urls?.apmap { url ->
            suspendSafeApiCall {
//                val resolved = WebViewResolver(
//                    Regex("""/getSources"""),
//                    // This is unreliable, generating my own link instead
////                  additionalUrls = listOf(Regex("""^.*transport=polling(?!.*sid=).*$"""))
//                ).resolveUsingWebView(getRequestCreator(url))
////              val extractorData = resolved.second.getOrNull(0)?.url?.toString()

                // ------- Main site -------

                // Possible without token

//                val response = app.get(url)
//                val key =
//                    response.document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
//                        .attr("src").substringAfter("render=")
//                val token = getCaptchaToken(mainUrl, key) ?: return@suspendSafeApiCall

                val serverId = url.substringAfterLast(".")
                val iframeLink =
                    app.get("$mainUrl/ajax/get_link/$serverId").parsed<IframeJson>().link
                        ?: return@suspendSafeApiCall

                // Some smarter ws11 or w10 selection might be required in the future.
                val extractorData =
                    "https://ws11.rabbitstream.net/socket.io/?EIO=4&transport=polling"

                extractRabbitStream(iframeLink, subtitleCallback, callback, extractorData) { it }
            }
        }

        return !urls.isNullOrEmpty()
    }

    data class PollingData(
        @JsonProperty("sid") val sid: String? = null,
        @JsonProperty("upgrades") val upgrades: ArrayList<String> = arrayListOf(),
        @JsonProperty("pingInterval") val pingInterval: Int? = null,
        @JsonProperty("pingTimeout") val pingTimeout: Int? = null
    )

    /*
    # python code to figure out the time offset based on code if necessary
    chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
    code = "Nxa_-bM"
    total = 0
    for i, char in enumerate(code[::-1]):
        index = chars.index(char)
        value = index * 64**i
        total += value
    print(f"total {total}")
    */
    private fun generateTimeStamp(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        var code = ""
        var time = APIHolder.unixTimeMS
        while (time > 0) {
            code += chars[(time % (chars.length)).toInt()]
            time /= chars.length
        }
        return code.reversed()
    }


    /**
     * Generates a session
     * */
    private suspend fun negotiateNewSid(baseUrl: String): PollingData? {
        // Tries multiple times
        for (i in 1..5) {
            val jsonText =
                app.get("$baseUrl&t=${generateTimeStamp()}").text.replaceBefore("{", "")
//            println("Negotiated sid $jsonText")
            parseJson<PollingData?>(jsonText)?.let { return it }
            delay(1000L * i)
        }
        return null
    }

    /**
     * Generates a new session if the request fails
     * @return the data and if it is new.
     * */
    private suspend fun getUpdatedData(
        response: NiceResponse,
        data: PollingData,
        baseUrl: String
    ): Pair<PollingData, Boolean> {
        if (!response.okhttpResponse.isSuccessful) {
            return negotiateNewSid(baseUrl)?.let {
                it to true
            } ?: data to false
        }
        return data to false
    }

    override suspend fun extractorVerifierJob(extractorData: String?) {
        if (extractorData == null) return

        val headers = mapOf(
            "Referer" to "https://rabbitstream.net/"
        )

        var data = negotiateNewSid(extractorData) ?: return
        // 40 is hardcoded, dunno how it's generated, but it seems to work everywhere.
        // This request is obligatory
        app.post(
            "$extractorData&t=${generateTimeStamp()}&sid=${data.sid}",
            json = "40", headers = headers
        )//.also { println("First post ${it.text}") }
        // This makes the second get request work, and re-connect work.
        val reconnectSid =
            parseJson<PollingData>(
                app.get(
                    "$extractorData&t=${generateTimeStamp()}&sid=${data.sid}",
                    headers = headers
                )
//                    .also { println("First get ${it.text}") }
                    .text.replaceBefore("{", "")
            ).sid
        // This response is used in the post requests. Same contents in all it seems.
        val authInt =
            app.get(
                "$extractorData&t=${generateTimeStamp()}&sid=${data.sid}",
                timeout = 60,
                headers = headers
            ).text
                //.also { println("Second get ${it}") }
                // Dunno if it's actually generated like this, just guessing.
                .toIntOrNull()?.plus(1) ?: 3

        // Prevents them from fucking us over with doing a while(true){} loop
        val interval = maxOf(data.pingInterval?.toLong()?.plus(2000) ?: return, 10000L)
        var reconnect = false
        var newAuth = false
        while (true) {
            val authData =
                when {
                    newAuth -> "40"
                    reconnect -> """42["_reconnect", "$reconnectSid"]"""
                    else -> authInt
                }

            val url = "${extractorData}&t=${generateTimeStamp()}&sid=${data.sid}"

            getUpdatedData(
                app.post(url, json = authData, headers = headers),
                data,
                extractorData
            ).also {
                newAuth = it.second
                data = it.first
            }

            //.also { println("Sflix post job ${it.text}") }
            Log.d(this.name, "Running ${this.name} job $url")

            val time = measureTimeMillis {
                // This acts as a timeout
                val getResponse = app.get(
                    "${extractorData}&t=${generateTimeStamp()}&sid=${data.sid}",
                    timeout = 60,
                    headers = headers
                )
//                    .also { println("Sflix get job ${it.text}") }
                if (getResponse.text.contains("sid")) {
                    reconnect = true
//                    println("Reconnecting")
                }
            }
            // Always waits even if the get response is instant, to prevent a while true loop.
            if (time < interval - 4000)
                delay(4000)
        }
    }

    private fun Element.toSearchResult(): SearchResponse {
        val img = this.select("img")
        val title = img.attr("title")
        val posterUrl = img.attr("data-src")
        val href = fixUrl(this.select("a").attr("href"))
        val isMovie = href.contains("/movie/")
        return if (isMovie) {
            MovieSearchResponse(
                title,
                href,
                this@FmoviesAPPProvider.name,
                TvType.Movie,
                posterUrl,
                null
            )
        } else {
            TvSeriesSearchResponse(
                title,
                href,
                this@FmoviesAPPProvider.name,
                TvType.Movie,
                posterUrl,
                null,
                null
            )
        }
    }

    companion object {
        fun String?.isValidServer(): Boolean {
            if (this.isNullOrEmpty()) return false
            if (this.equals("UpCloud", ignoreCase = true) || this.equals(
                    "Vidcloud",
                    ignoreCase = true
                ) || this.equals("RapidStream", ignoreCase = true)
                || this.equals("DoodStream", ignoreCase = true) ||
                this.equals("Voe", ignoreCase = true)
                || this.equals("Mixdrop", ignoreCase = true)
            ) return true
            return false
        }

        // For re-use in Zoro
        fun YesMoviesProvider.Sources.toExtractorLink(
            caller: MainAPI,
            name: String,
            extractorData: String? = null
        ): List<ExtractorLink>? {
            return this.file?.let { file ->
                //println("FILE::: $file")
                val isM3u8 = URI(this.file).path.endsWith(".m3u8") || this.type.equals(
                    "hls",
                    ignoreCase = true
                )
                if (isM3u8) {
                    M3u8Helper().m3u8Generation(M3u8Helper.M3u8Stream(this.file, null), true)
                        .map { stream ->
                            //println("stream: ${stream.quality} at ${stream.streamUrl}")
                            val qualityString = if ((stream.quality ?: 0) == 0) label
                                ?: "" else "${stream.quality}p"
                            ExtractorLink(
                                caller.name,
                                "${caller.name} $qualityString $name",
                                stream.streamUrl,
                                caller.mainUrl,
                                getQualityFromName(stream.quality.toString()),
                                true,
                                extractorData = extractorData
                            )
                        }
                } else {
                    listOf(ExtractorLink(
                        caller.name,
                        this.label?.let { "${caller.name} - $it" } ?: caller.name,
                        file,
                        caller.mainUrl,
                        getQualityFromName(this.type ?: ""),
                        false,
                        extractorData = extractorData
                    ))
                }
            }
        }

        fun YesMoviesProvider.Tracks.toSubtitleFile(): SubtitleFile? {
            return this.file?.let {
                SubtitleFile(
                    this.label ?: "Unknown",
                    it
                )
            }
        }


        suspend fun MainAPI.extractRabbitStream(
            url: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            /** Used for extractorLink name, input: Source name */
            extractorData: String? = null,
            nameTransformer: (String) -> String
        ) {
            // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> https://rapid-cloud.ru/embed-6
            if (url.contains("rapid-cloud.ru")  || url.contains("rabbitstream")) {
                val mainIframeUrl =
                    url.substringBeforeLast("/")
                val mainIframeId = url.substringAfterLast("/")
                    .substringBefore("?") // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> dcPOVRE57YOT
                val iframe = app.get(url, referer = mainUrl)
                val iframeKey =
                    iframe.document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                        .attr("src").substringAfter("render=")
                val iframeToken = APIHolder.getCaptchaToken(url, iframeKey)
                val number =
                    Regex("""recaptchaNumber = '(.*?)'""").find(iframe.text)?.groupValues?.get(1)

                val mapped = app.get(
                    "${
                        mainIframeUrl.replace(
                            "/embed",
                            "/ajax/embed"
                        )
                    }/getSources?id=$mainIframeId&_token=$iframeToken&_number=$number",
                    referer = mainUrl,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "*/*",
                        "Accept-Language" to "en-US,en;q=0.5",
//                        "Cache-Control" to "no-cache",
                        "Connection" to "keep-alive",
//                        "Sec-Fetch-Dest" to "empty",
//                        "Sec-Fetch-Mode" to "no-cors",
//                        "Sec-Fetch-Site" to "cross-site",
//                        "Pragma" to "no-cache",
//                        "Cache-Control" to "no-cache",
                        "TE" to "trailers"
                    )
                ).parsed<YesMoviesProvider.SourceObject>()

                mapped.tracks?.apmap { track ->
                    track?.toSubtitleFile()?.let { subtitleFile ->
                        subtitleCallback.invoke(subtitleFile)
                    }
                }

                val list = listOf(
                    mapped.sources to "source 1",
                    mapped.sources1 to "source 2",
                    mapped.sources2 to "source 3",
                    mapped.sourcesBackup to "source backup"
                )

                list.apmap { subList ->
                    subList.first?.apmap { source ->
                        source?.toExtractorLink(this, nameTransformer(subList.second), extractorData)
                            ?.forEach(callback)
                    }
                }
            }
        }
    }
}

