package com.lagradost.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName

open class Ashdi : ExtractorApi() {
    override var name = "Ashdi"
    override var mainUrl = "https://ashdi.vip"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = referer).document
        val m3u8Url = response.select("script").html().let {
            Regex("file:\"(.*?)\"").find(it)?.groupValues?.get(1)
        } ?: return null

        return listOf(
            ExtractorLink(
                this.name, // Назва екстрактора
                this.name, // Назва джерела (буде доповнена в провайдері)
                m3u8Url,
                referer ?: mainUrl,
                getQualityFromName(""),
                isM3u8 = true
            )
        )
    }
}
