package com.lagradost.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

// Модель для парсингу JSON відповіді
data class MoonResponse(
    val success: Boolean,
    val data: String?
)

open class MoonExtractor : ExtractorApi() {
    override var name = "Moon"
    override var mainUrl = "https://moon.anime-art.in.ua" // Цей домен може змінюватись
    override val requiresReferer = true

    // Ключ та IV для дешифрування, адаптовані з anitubeapp
    private val key = "secret key".toByteArray(Charsets.UTF_8)
    private val iv = "initialization vector".toByteArray(Charsets.UTF_8)

    private fun decrypt(data: String): String {
        return try {
            val decodedData = Base64.decode(data, Base64.DEFAULT)
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            String(cipher.doFinal(decodedData))
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val extractedLinks = mutableListOf<ExtractorLink>()
        val doc = app.get(url, referer = referer).document

        // Шукаємо зашифровані дані на сторінці плеєра
        val script = doc.selectFirst("script:containsData(const player =)")?.data() ?: return emptyList()
        val encryptedData = Regex("""data: '(.*?)'""").find(script)?.groupValues?.get(1) ?: return emptyList()

        // Робимо запит до API плеєра для отримання JSON
        val ajaxUrl = "${mainUrl}/api/player"
        val response = app.post(
            ajaxUrl,
            headers = mapOf(
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest"
            ),
            data = mapOf("data" to encryptedData)
        ).text

        try {
            val moonResponse = parseJson<MoonResponse>(response)
            if (moonResponse.success && moonResponse.data != null) {
                val decryptedData = decrypt(moonResponse.data)

                // Витягуємо посилання на m3u8 плейлисти з розшифрованих даних
                // Цей regex шукає якість та посилання на плейлист
                val masterPlaylist = Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=\d+x(\d+).*?\n(.*?)\n""").findAll(decryptedData)

                masterPlaylist.forEach { match ->
                    val quality = match.groupValues[1]
                    val videoUrl = match.groupValues[2]

                    // Створюємо абсолютний URL, якщо потрібно
                    val finalUrl = if (videoUrl.startsWith("http")) {
                        videoUrl
                    } else {
                        // Базовий URL для відносних посилань
                        val baseUrl = url.substringBeforeLast("/")
                        "$baseUrl/$videoUrl"
                    }

                    extractedLinks.add(
                        ExtractorLink(
                            this.name,
                            "${this.name} ${quality}p", // Назва джерела, напр. "Moon 720p"
                            finalUrl,
                            url, // Referer
                            getQualityFromString(quality),
                            isM3u8 = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return extractedLinks
    }
}
