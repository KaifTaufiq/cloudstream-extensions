package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.Locale

class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Others,
        TvType.Live,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "live" to "Live",
        "trending_podcasts_episodes" to "Podcasts",
        "trending_gaming" to "Gaming",
        "trending_music" to "Music",
        "trending_movies_and_shows" to "Movies & TV"
    )

    private val service = ServiceList.YouTube

    private val pageCache = mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val key = request.data
        if (page == 1) pageCache.remove(key)

        val extractor = getKioskExtractor(request.data)

        extractor.forceContentCountry(ContentCountry(Locale.getDefault().country))

        val pageData = if (page == 1) {
            extractor.fetchPage()
            extractor.initialPage.also {
                pageCache[key] = it.nextPage
            }
        } else {
            val next = pageCache[key] ?: return newHomePageResponse(emptyList(), false)
            extractor.getPage(next).also {
                pageCache[key] = it.nextPage
            }
        }

        val results = pageData.items.map {
            it.toSearchResponse()
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name.ifEmpty { "Trending" },
                    results,
                    true
                )
            ),
            pageData.hasNextPage()
        )
    }

    private val searchPageCache = mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val extractor = service.getSearchExtractor(query)
        extractor.forceContentCountry(ContentCountry(Locale.getDefault().country))

        val pageData = if (!searchPageCache.containsKey(query)) {
            extractor.fetchPage()
            extractor.initialPage.also {
                searchPageCache[query] = it.nextPage
            }
        } else {
            val next = searchPageCache[query] ?: return newSearchResponseList(emptyList(), false)
            extractor.getPage(next).also {
                searchPageCache[query] = it.nextPage
            }
        }

        val results = pageData.items.map {
            it.toSearchResponse()
        }

        return newSearchResponseList(
            results,
            pageData.hasNextPage()
        )
    }

    private fun getKioskExtractor(kioskId: String?): KioskExtractor<out InfoItem> {
        val service = ServiceList.YouTube
        return if (kioskId.isNullOrBlank()) {
            service.kioskList.getDefaultKioskExtractor(null)
        } else {
            service.kioskList.getExtractorById(kioskId, null)
        }
    }

    private fun InfoItem.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            name ?: "Unknown",
            url ?: "",
            TvType.Others
        ) {
            posterUrl = thumbnails.lastOrNull()?.url
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = extractVideoId(url)
            ?: throw RuntimeException("Invalid YouTube URL")

        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        val info = StreamInfo.getInfo(extractor)

        return newMovieLoadResponse(
            info.name,
            url,
            if (info.streamType?.name?.contains("LIVE") == true)
                TvType.Live else TvType.Others,
            videoId
        ) {
            plot = info.description.content
            posterUrl = info.thumbnails.lastOrNull()?.url
            duration = info.duration.toInt()

            info.uploaderName?.takeIf { it.isNotBlank() }?.let { uploader ->
                actors = listOf(
                    ActorData(
                        Actor(
                            uploader,
                            info.uploaderAvatars.lastOrNull()?.url ?: ""
                        )
                    )
                )
            }

            tags = info.tags?.take(5)?.toList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(
            "https://youtube.com/watch?v=$data",
            subtitleCallback,
            callback
        )
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)([a-zA-Z0-9_-]{11})",
            "v=([a-zA-Z0-9_-]{11})"
        )

        return patterns.firstNotNullOfOrNull {
            it.toRegex().find(url)?.groupValues?.get(1)
        }
    }
}