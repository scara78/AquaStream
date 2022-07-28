package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class SeriesMetroProvider: MainAPI() {
    override var mainUrl = "https://seriesmetro.net"
    override var name = "SeriesMetro"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
    )

    override suspend fun getMainPage(): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val soup = app.get(mainUrl).document
        val list = listOf(
            Pair("Últimas series agregadas",".series article"),
            Pair("Series Populares", ".serie.sm")
        )

        val newseries = soup.select(".section.episodes article").map {
            val title = it.selectFirst(".entry-header .tvshow")!!.text()
            val poster = it.selectFirst(".post-thumbnail figure img")!!.attr("src")
            val href = it.selectFirst("a.lnk-blk")!!.attr("href").replace(Regex("(-(\\d+)x(\\d+).*)"),"")
                .replace("/episode","/serie")
            TvSeriesSearchResponse(
                title,
                fixUrl(href),
                this.name,
                TvType.TvSeries,
                fixUrl(poster),
                null,
                null
            )
        }

        items.add(HomePageList("Agregados Recientemente", newseries))

        list.map { (name, csselement) ->
            val home = soup.select(csselement).map {
                val title = it.selectFirst(".entry-title")!!.text()
                val poster = it.selectFirst(".post-thumbnail figure img")!!.attr("src")
                val href = it.selectFirst("a.lnk-blk")!!.attr("href")
                TvSeriesSearchResponse(
                    title,
                    fixUrl(href),
                    this.name,
                    TvType.TvSeries,
                    fixUrl(poster),
                    null,
                    null
                )
            }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select(".results-post article").map {
            val title = it.selectFirst(".entry-title")!!.text()
            val href = it.selectFirst("a.lnk-blk")!!.attr("href")
            val image = it.selectFirst(".post-thumbnail figure img")!!.attr("src")
                TvSeriesSearchResponse(
                    title,
                    fixUrl(href),
                    this.name,
                    TvType.TvSeries,
                    fixUrl(image),
                    null,
                    null
                )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val soup = app.get(url).document
        val title = soup.selectFirst(".main-site article .entry-title")!!.text()
        val year = soup.selectFirst(".main-site article span.date")!!.text().toIntOrNull()
        val description = soup.select(".main-site article .entry-content > p").text().trim()

        var poster = soup.selectFirst(".main-site article .post-thumbnail figure img")!!.attr("src")
        if (poster.contains("data:image")) {
            poster = soup.selectFirst(".main-site article .post-thumbnail figure img")!!.attr("data-lazy-src")
        }
        val tags = soup.select(".tagcloud a").map { it.text() }
        val datapost = soup.select(".sel-temp a").attr("data-post")
        val dataobject = soup.select("div.widget .aa-cn").attr("data-object")
        val dataseason = soup.select(".sel-temp a").map {
            it.attr("data-season")
        }
        val episodes = ArrayList<Episode>()
        val episs = dataseason.apmap { season ->
            val response = app.post("$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "Host" to "seriesmetro.net",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to "https://seriesmetro.net",
                    "DNT" to "1",
                    "Alt-Used" to "seriesmetro.net",
                    "Connection" to "keep-alive",
                    "Referer" to url,
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",),
                data = mapOf(
                    Pair("action","action_select_season"),
                    Pair("season",season),
                    Pair("post",datapost),
                    Pair("object",dataobject))
            ).document
           val a = response.select("#episode_by_temp > li > a").map {
              val link = it.attr("href")
              val aa = link.replace("-capitulo-","x")
              val regexseasonepnum = Regex("((\\d+)x(\\d+))")
              val test = regexseasonepnum.find(aa)?.destructured?.component1() ?: ""
               val seasonid = test.let { str ->
                   str.split("x").mapNotNull { subStr -> subStr.toIntOrNull() }
               }
               val isValid = seasonid.size == 2
               val episode = if (isValid) seasonid.getOrNull(1) else null
               val seasonint = if (isValid) seasonid.getOrNull(0) else null
               episodes.add(Episode(
                   link,
                   season = seasonint,
                   episode = episode
               ))
            }
        }




        val recommendations =
            soup.select(".serie.sm").mapNotNull { element ->
                val recTitle = element.selectFirst(".entry-title")!!.text() ?: return@mapNotNull null
                var image = element.selectFirst(".post-thumbnail figure img")!!.attr("data-lazy-src")
                if (image.isEmpty())   {
                    image = element.selectFirst(".post-thumbnail figure img")!!.attr("src")
                }
                val recUrl = fixUrl(element.selectFirst("a.lnk-blk")!!.attr("href"))
                TvSeriesSearchResponse(
                    recTitle,
                    recUrl,
                    this.name,
                    TvType.Movie,
                    fixUrl(image),
                    year = null
                )
            }

        return  newTvSeriesLoadResponse(title,
            url, TvType.TvSeries, episodes,){
            this.posterUrl = fixUrl(poster)
            this.plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val soup = app.get(data).document
        val dataterm = soup.select(".video.aa-tb.hdd.anm-a.on").attr("data-term")
        val dataop = soup.select("ul.aa-tbs-video li a").map { it.attr("data-opt") }
        dataop.apmap { serverid ->
            val response = app.post("$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "Host" to "seriesmetro.net",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to "https://seriesmetro.net",
                    "DNT" to "1",
                    "Alt-Used" to "seriesmetro.net",
                    "Connection" to "keep-alive",
                    "Referer" to data,
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",),
                data = mapOf(
                    Pair("action","action_player_series"),
                    Pair("ide",serverid),
                    Pair("term_id",dataterm))
            ).document
            val embedlink = response.select("body iframe").attr("src")
            val secondresponse = app.get(embedlink).document
            val trueembedlink = secondresponse.select(".Video iframe").attr("src")
            loadExtractor(trueembedlink, data,  subtitleCallback, callback)
        }
        return true
    }
}