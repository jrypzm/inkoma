package eu.kanade.tachiyomi.extension.en.testsource

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup

/** Written in the extensions-lib 1.6 style (suspend API, no request/parse helpers). */
class TestSource : HttpSource() {
    override val name = "Test Source"
    override val lang = "en"
    override val supportsLatest = false
    override val baseUrl: String = System.getProperty("testsource.baseUrl", "http://127.0.0.1:1")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val doc = client.newCall(GET("$baseUrl/popular?page=$page", headers)).awaitSuccess().asJsoup()
        val mangas = doc.select("div.manga a").map { a ->
            SManga.create().apply {
                setUrlWithoutDomain(a.attr("abs:href"))
                title = a.text()
                thumbnail_url = a.attr("data-cover")
            }
        }
        return MangasPage(mangas, doc.selectFirst("a.next") != null)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(getPopularManga(page).mangas.filter { it.title.contains(query, true) }, false)

    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val doc = client.newCall(GET(baseUrl + manga.url, headers)).awaitSuccess().asJsoup()
        val details = manga.copy().apply {
            description = doc.selectFirst("p.desc")?.text()
            initialized = true
        }
        val chs = doc.select("li.chapter a").map { a ->
            SChapter.create().apply {
                setUrlWithoutDomain(a.attr("abs:href"))
                name = a.text()
            }
        }
        return SMangaUpdate(details, chs)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        listOf(Page(0, imageUrl = "$baseUrl/img/1.png"), Page(1, imageUrl = "$baseUrl/img/2.png"))

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url
}
