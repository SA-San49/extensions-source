package eu.kanade.tachiyomi.animeextension.en.anv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CatalogError(
    val message: String? = null,
)

@Serializable
data class PageEnvelope(
    val data: PageData? = null,
    val errors: List<CatalogError>? = null,
    val error: String? = null,
) {
    fun page(): MediaPage = data?.page ?: throw Exception(errors?.firstOrNull()?.message ?: error ?: "No results found")
}

@Serializable
data class PageData(
    @SerialName("Page") val page: MediaPage? = null,
)

@Serializable
data class MediaPage(
    val media: List<Media>? = null,
    val pageInfo: PageInfo? = null,
)

@Serializable
data class PageInfo(
    val currentPage: Int? = null,
    val lastPage: Int? = null,
    val hasNextPage: Boolean? = null,
    val total: Int? = null,
)

@Serializable
data class DetailEnvelope(
    val data: DetailData? = null,
    val errors: List<CatalogError>? = null,
    val error: String? = null,
) {
    fun media(): Media = data?.media ?: throw Exception(errors?.firstOrNull()?.message ?: error ?: "Anime not found")
}

@Serializable
data class DetailData(
    @SerialName("Media") val media: Media? = null,
)

@Serializable
data class Media(
    val id: Long? = null,
    val idMal: Long? = null,
    val slug: String? = null,
    val title: Title? = null,
    val synonyms: List<String>? = null,
    val description: String? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val nextAiringEpisode: NextAiringEpisode? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val genres: List<String>? = null,
    val averageScore: Int? = null,
    val studios: Studios? = null,
    val isAdult: Boolean? = null,
    val streamingEpisodes: List<StreamingEpisode>? = null,
)

@Serializable
data class Title(
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
data class CoverImage(
    val large: String? = null,
    val extraLarge: String? = null,
)

@Serializable
data class NextAiringEpisode(
    val episode: Int? = null,
)

@Serializable
data class Studios(
    val nodes: List<Studio>? = null,
)

@Serializable
data class Studio(
    val name: String? = null,
)

@Serializable
data class StreamingEpisode(
    val title: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
)

@Serializable
data class ScheduleEnvelope(
    val schedules: List<Schedule>? = null,
)

@Serializable
data class Schedule(
    val id: Long? = null,
    val episode: Int? = null,
    val airingAt: Long? = null,
    val media: Media? = null,
)

@Serializable
data class EpisodeEnvelope(
    val status: String? = null,
    val reason: String? = null,
    val variants: List<EpisodeVariant>? = null,
)

@Serializable
data class EpisodeVariant(
    val id: String? = null,
    val label: String? = null,
    val sources: List<EpisodeSource>? = null,
)

@Serializable
data class EpisodeSource(
    val id: String? = null,
    val label: String? = null,
    val url: String? = null,
    val type: String? = null,
    val tracks: List<EpisodeTrack>? = null,
)

@Serializable
data class EpisodeTrack(
    val id: String? = null,
    val src: String? = null,
    val type: String? = null,
    val label: String? = null,
    val language: String? = null,
    val default: Boolean? = null,
)
