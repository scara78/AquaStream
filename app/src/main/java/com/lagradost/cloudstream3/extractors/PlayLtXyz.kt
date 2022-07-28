package com.lagradost.cloudstream3.extractors

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.*

class PlayLtXyz: ExtractorApi() {
    override val name: String = "PlayLt"
    override val mainUrl: String = "https://play.playlt.xyz"
    override val requiresReferer = true

    private data class ResponseData(
        @JsonProperty("data") val data: String?
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val extractedLinksList = mutableListOf<ExtractorLink>()
        //Log.i(this.name, "Result => (url) $url")
        var idUser = ""
        var idFile = ""
        var bodyText = ""
        val doc = app.get(url, referer = referer).document
        //Log.i(this.name, "Result => (url, script) $url / ${doc.select("script")}")
        doc.select("script")?.forEach {
            if (it != null) {
                val text = it.toString()
                if (text.contains("var idUser")) {
                    bodyText = text
                    //Log.i(this.name, "Result => (bodyText) $bodyText")
                }
            }
        }
        //Log.i(this.name, "Result => (bodyText) $bodyText")
        if (bodyText.isNotBlank()) {
            idUser = "(?<=var idUser = \")(.*)(?=\";)".toRegex().find(bodyText)
                ?.groupValues?.get(0).toString()

            idFile = "(?<=var idfile = \")(.*)(?=\";)".toRegex().find(bodyText)
                ?.groupValues?.get(0).toString()
        }
        Log.i(this.name, "Result => (idUser, idFile) $idUser / $idFile")
        if (idUser.isNotBlank() && idFile.isNotBlank()) {
            val sess = HttpSession()
            val ajaxHead = mapOf(
                Pair("Origin", "https://play.playlt.xyz"),
                Pair("Referer", "https://play.playlt.xyz"),
                Pair("Sec-Fetch-Site", "same-site"),
                Pair("Sec-Fetch-Mode", "cors"),
                Pair("Sec-Fetch-Dest", "empty")
            )
            val ajaxData = mapOf(
                Pair("referrer", referer),
                Pair("typeend", "html")
            )

            //idUser = 608f7c85cf0743547f1f1b4e
            val posturl = "https://api-plhq.playlt.xyz/apiv5/$idUser/$idFile"
            val data = sess.post(posturl, headers = ajaxHead, data = ajaxData)
            Log.i(this.name, "Result => (posturl) $posturl")
            if (data.statusCode == 200) {
                val itemstr = data.text
                Log.i(this.name, "Result => (data) $itemstr")
                mapper.readValue<ResponseData>(itemstr).let { item ->
                    val linkUrl = item.data ?: ""
                    if (linkUrl.isNotBlank()) {
                        extractedLinksList.add(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = linkUrl,
                                referer = url,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    }
                }
            }
        }
        return extractedLinksList
    }
}