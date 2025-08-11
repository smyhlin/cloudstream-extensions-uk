package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class AnitubeinuaProvider : MainAPI() {
    override var mainUrl = "https://anitube.in.ua"
    override var name = "Anitube.in.ua"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Останні надходження",
        "$mainUrl/f-s/anime/page/" to "Аніме",
        "$mainUrl/f-s/anime-serialy/page/" to "Аніме серіали",
        "$mainUrl/f-s/anime-povnometrazhni/page/" to "Аніме фільми",
        "$mainUrl/f-s/anime-ova/page/" to "OVA / ONA",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.story").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.story_c > a")?.text() ?: return null
        val href = this.selectFirst("div.story_c > a")?.attr("href") ?: return null
        val posterUrl = mainUrl + this.selectFirst("div.story_ava > a > img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query
            )
        ).document

        return document.select("div.story").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("div.story_c > h1")?.text() ?: return null
        val poster = mainUrl + doc.selectFirst("div.story_ava img")?.attr("src")
        val description = doc.selectFirst("div.full-text")?.text()
        val year = doc.selectFirst("a[href*=\"/f-r/\"]")?.text()?.toIntOrNull()

        // Отримуємо список епізодів з першого доступного плеєра, щоб сформувати базовий список
        val initialEpisodes = doc.select("ul.series-list > li > a")

        // Перевірка, чи це фільм (немає списку серій)
        if (initialEpisodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }

        val episodes = initialEpisodes.mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            val name = el.text()
            val episodeNum = name.substringAfter(" ").substringBefore(" ").toIntOrNull()
            newEpisode(href) {
                this.name = name
                this.episode = episodeNum
            }
        }

        return newTvShowLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String, // Це URL епізоду
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Витягуємо news_id, він потрібен для AJAX запитів
        val newsId = doc.selectFirst("script:containsData(news_id)")?.data()
            ?.let { Regex("""news_id: ?"(\d+)"""").find(it)?.groupValues?.get(1) }
            ?: return false

        // Знаходимо всі вкладки з озвученнями
        val dubs = doc.select("ul.tabs-box-item > li")
        if (dubs.isEmpty()) return false

        // Паралельно обробляємо кожне озвучення
        dubs.apmap { dubElement ->
            try {
                val dubName = dubElement.text()
                val dubId = dubElement.attr("data-id")

                // Робимо AJAX-запит, щоб отримати iframe плеєра для цієї серії та цього озвучення
                val ajaxResponse = app.post(
                    "$mainUrl/index.php?do=ajax&action=getEpisodes",
                    data = mapOf(
                        "news_id" to newsId,
                        "voicer_id" to dubId
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).document

                // Знаходимо iframe або дані плеєра у відповіді
                val playerUrl = ajaxResponse.selectFirst("iframe")?.attr("src") ?: return@apmap

                // Викликаємо відповідний екстрактор
                loadExtractor(playerUrl, data, subtitleCallback) { link ->
                    // ВАЖЛИВО: Додаємо назву озвучення до назви джерела
                    callback.invoke(
                        link.copy(name = "${this.name} - $dubName")
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return true
    }
}
