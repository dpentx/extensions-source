package eu.kanade.tachiyomi.extension.tr.juratempest

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParseDate
import okhttp3.HttpUrl
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class JuraTempest : KeiSource() {

    // Popular
    // No dedicated catalog page exists yet (`/explore` is under construction), so the
    // homepage's highlight carousel is used as a stand-in. Single page, no pagination.
    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val document = client.get("$baseUrl/").asJsoup()
        val mangas = document.select("div.swiper-slide").mapNotNull { slide ->
            val link = slide.selectFirst("a[href^=/explore/]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = slide.selectFirst("div[class*=\"aspect-2/3\"] img")?.absUrl("src")
            }
        }

        return MangasPage(mangas, false)
    }

    // Latest
    // Also sourced from the homepage ("Son Yüklenenler" section, a feed of recently
    // updated chapters) until `/explore` ships. Single page, no pagination.
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val document = client.get("$baseUrl/").asJsoup()
        val section = document.selectFirst("h2:containsOwn(Son Yüklenenler)")?.closest("section")
            ?: return MangasPage(emptyList(), false)

        val mangas = section.select("a[href^=/explore/]").mapNotNull { element ->
            val slug = element.attr("href").substringAfter("/explore/").substringBefore("/")
            if (slug.isEmpty()) return@mapNotNull null

            SManga.create().apply {
                url = "/explore/$slug"
                title = element.selectFirst("span.truncate.font-semibold")?.text() ?: return@mapNotNull null
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, false)
    }

    // Search
    // The site's search box calls an internal API this extension doesn't reverse-engineer
    // yet, and the browse/catalog page is still under construction, so plain-text search
    // isn't available for now.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.size != 2 || segments[0] != "explore") return null

        val manga = SManga.create().apply {
            this.url = "/explore/${segments[1]}"
        }

        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply {
                initialized = true
                this.url = manga.url
            }
    }

    // Details & Chapters
    // Both live on the same manga page, so they're always fetched and parsed together.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()

        val updatedManga = SManga.create().apply {
            url = manga.url
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = document.selectFirst("div[data-slot=manga-detail-hero-cover] img")?.absUrl("src")
            description = document.selectFirst("p[data-slot=manga-detail-hero-description]")?.text()
            genre = document.select("div[data-slot=manga-detail-tags-genres] span[data-slot=badge]")
                .joinToString { it.text() }
            status = document.selectFirst("div[data-slot=manga-detail-metadata] span:has(svg.lucide-clock)")
                ?.text()
                ?.let(::parseStatus)
                ?: SManga.UNKNOWN
        }

        val chapterList = document.select("a[data-slot=chapter-row]").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.selectFirst("span.truncate.font-medium")!!.text()
                chapter_number = chapterNumberRegex.find(name)?.value?.toFloatOrNull() ?: -1f
                date_upload = element.selectFirst("span.text-muted-foreground.text-xs")?.text()
                    ?.let { dateFormat.tryParseDate(it, istanbulZone) } ?: 0L
            }
        }

        return SMangaUpdate(updatedManga, chapterList)
    }

    private fun parseStatus(status: String): Int {
        val text = status.lowercase()
        return when {
            text.contains("devam") -> SManga.ONGOING
            text.contains("tamamlandı") -> SManga.COMPLETED
            text.contains("ara verildi") -> SManga.ON_HIATUS
            text.contains("iptal") || text.contains("bırakıldı") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()

        return document.select("div[data-slot=reader-images] img").mapIndexed { index, img ->
            Page(index, imageUrl = img.absUrl("src"))
        }
    }

    companion object {
        private val chapterNumberRegex = """\d+(\.\d+)?""".toRegex()
        private val istanbulZone = ZoneId.of("Europe/Istanbul")
        private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("tr"))
    }
}
