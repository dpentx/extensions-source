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
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import java.time.Instant

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
    // The site's own search box calls an internal API this extension doesn't
    // reverse-engineer, and the browse/catalog page (`/explore`) is still under
    // construction. But `sitemap.xml` lists every manga's URL (slug), so search works by
    // fuzzy-matching the query against those slugs, then fetching real titles/covers only
    // for the matches - a handful of requests instead of scraping a full catalog page.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)

        val sitemapXml = client.get("$baseUrl/sitemap.xml").body.string()
        val slugs = sitemapEntryRegex.findAll(sitemapXml).map { it.groupValues[1] }.distinct().toList()

        val normalizedQuery = normalizeForSearch(query)
        val matchedSlugs = slugs.filter { normalizeForSearch(it).contains(normalizedQuery) }

        val pageSlugs = matchedSlugs.drop((page - 1) * SEARCH_PAGE_SIZE).take(SEARCH_PAGE_SIZE)
        val mangas = pageSlugs.map { slug ->
            val document = client.get("$baseUrl/explore/$slug").asJsoup()
            SManga.create().apply {
                url = "/explore/$slug"
                title = document.selectFirst("h1")?.text() ?: slug
                thumbnail_url = document.selectFirst("div[data-slot=manga-detail-hero-cover] img")?.absUrl("src")
            }
        }

        return MangasPage(mangas, matchedSlugs.size > page * SEARCH_PAGE_SIZE)
    }

    private fun normalizeForSearch(text: String): String = text.lowercase()
        .replace("ç", "c")
        .replace("ş", "s")
        .replace("ğ", "g")
        .replace("ü", "u")
        .replace("ö", "o")
        .replace("ı", "i")
        .replace("i̇", "i")
        .replace(Regex("[^a-z0-9]+"), "")

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
    //
    // The chapter list rendered in the DOM is paginated client-side (10 rows per page) with
    // no addressable URL. A React hydration payload embedded in the page normally carries
    // the full list, but it's unreliable in practice - the site doesn't appear to have any
    // bot protection, so plain requests, headers, and even a real WebView all get the same
    // 10-row page. Rather than chase that further, the missing chapters are instead
    // confirmed to exist by requesting them directly: probing backward every 10 chapters
    // from the lowest visible one, narrowing in on the exact starting chapter with a binary
    // search once a gap is found, so series translated starting mid-run (chapter 1 genuinely
    // 404s) still get everything down to their real first chapter instead of nothing.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val html = client.get(baseUrl + manga.url).body.string()
        val document = Jsoup.parse(html, baseUrl + manga.url)

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

        val hydratedChapters = chapterEntryRegex.findAll(html).map { match ->
            val (slug, number, title, isSpecial, createdAt) = match.destructured
            SChapter.create().apply {
                url = "${manga.url}/$slug"
                name = title.replace("\\\"", "\"").replace("\\\\", "\\")
                chapter_number = number.toFloatOrNull() ?: -1f
                date_upload = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)
                scanlator = if (isSpecial == "!0") "Özel" else null
            }
        }.toList()

        val chapterList = hydratedChapters.ifEmpty {
            val visibleChapters = document.select("a[data-slot=chapter-row]").map { element ->
                SChapter.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    name = element.selectFirst("span.truncate.font-medium")!!.text()
                    chapter_number = element.selectFirst("div.size-10")?.text()?.trim()?.toFloatOrNull() ?: -1f
                }
            }
            visibleChapters + fillMissingChapters(manga.url, visibleChapters)
        }

        return SMangaUpdate(updatedManga, chapterList)
    }

    // Confirms whether chapters below [visibleChapters] actually exist by requesting them
    // directly, rather than guessing. First sweeps backward every 10 chapters until a probe
    // fails (or chapter 1 is reached), then binary-searches the last 10-chapter window to
    // pin down the exact starting chapter, so partial translations (starting well after
    // chapter 1) still get everything down to their real first chapter.
    private suspend fun fillMissingChapters(mangaUrl: String, visibleChapters: List<SChapter>): List<SChapter> {
        val lowestWhole = visibleChapters
            .map { it.chapter_number }
            .filter { it > 0 && it == it.toInt().toFloat() }
            .minOrNull()
            ?.toInt()
            ?: return emptyList()

        if (lowestWhole <= 1) return emptyList()

        var lastGood = lowestWhole
        var probe = lowestWhole - 1
        var failedAt: Int? = null
        while (probe >= 1) {
            if (chapterExists("$baseUrl$mangaUrl/$probe")) {
                lastGood = probe
                if (probe == 1) break
                probe = maxOf(1, probe - 10)
            } else {
                failedAt = probe
                break
            }
        }

        val lowerBound = failedAt?.let { badChapter ->
            var lo = badChapter + 1
            var hi = lastGood
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (chapterExists("$baseUrl$mangaUrl/$mid")) hi = mid else lo = mid + 1
            }
            lo
        } ?: 1

        if (lowerBound >= lowestWhole) return emptyList()

        return ((lowestWhole - 1) downTo lowerBound).map { n ->
            SChapter.create().apply {
                url = "$mangaUrl/$n"
                name = "Bölüm $n"
                chapter_number = n.toFloat()
            }
        }
    }

    private suspend fun chapterExists(url: String): Boolean = try {
        val response = client.get(url)
        val ok = response.isSuccessful
        response.close()
        ok
    } catch (e: Exception) {
        false
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
        private const val SEARCH_PAGE_SIZE = 20

        // Matches a manga entry in sitemap.xml, e.g.:
        // <loc>https://juratempe.st/explore/haimiya-senpai-dehset-derecede-sevimli</loc>
        private val sitemapEntryRegex = Regex("""<loc>[^<]*/explore/([a-z0-9-]+)</loc>""")

        // Matches one chapter record from the embedded hydration payload, e.g.:
        // slug:"38-5",number:38.5,title:"Bölüm 38.5",isSpecial:!0,createdAt:$R[91]=new Date("2026-08-19T15:11:32.402Z")
        // The "$R[91]=" part is a minifier-assigned registry reference whose name/index
        // can change between site deploys, so it's matched loosely rather than pinned.
        // Kept as an opportunistic bonus even though it's never actually matched anything
        // on-device so far - harmless if the payload ever does show up.
        private val chapterEntryRegex = Regex(
            """slug:"([^"]+)",number:([0-9.]+),title:"((?:[^"\\]|\\.)*)",isSpecial:(!0|!1),createdAt:(?:${'$'}\w+\[\d+]=)?new Date\("([^"]+)"\)""",
        )
    }
}
