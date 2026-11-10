package eu.kanade.tachiyomi.animeextension.en.anv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

/**
 * Filters are sent as `BrowseAnime` variables to `POST /api/catalog`,
 * which is a thin proxy over an AniList-shaped catalog.
 */
object AnvFilters {

    data class Option(val label: String, val value: String?)

    open class OptionFilter(name: String, private val options: List<Option>) :
        AnimeFilter.Select<String>(name, options.map { it.label }.toTypedArray()) {
        val selected: String?
            get() = options.getOrNull(state)?.value
    }

    class SortFilter : OptionFilter(
        "Sort by",
        listOf(
            Option("Default (best match)", null),
            Option("Trending", "TRENDING_DESC"),
            Option("Popularity", "POPULARITY_DESC"),
            Option("Top rated", "SCORE_DESC"),
            Option("Newest", "START_DATE_DESC"),
            Option("Recently updated", "UPDATED_AT_DESC"),
            Option("Most favourited", "FAVOURITES_DESC"),
            Option("Title A-Z", "TITLE_ROMAJI"),
            Option("Most episodes", "EPISODES_DESC"),
        ),
    )

    class FormatFilter : OptionFilter(
        "Format",
        listOf(
            Option("Any", null),
            Option("TV", "TV"),
            Option("TV Short", "TV_SHORT"),
            Option("Movie", "MOVIE"),
            Option("Special", "SPECIAL"),
            Option("OVA", "OVA"),
            Option("ONA", "ONA"),
            Option("Music", "MUSIC"),
        ),
    )

    class StatusFilter : OptionFilter(
        "Status",
        listOf(
            Option("Any", null),
            Option("Airing", "RELEASING"),
            Option("Finished", "FINISHED"),
            Option("Upcoming", "NOT_YET_RELEASED"),
        ),
    )

    class SeasonFilter : OptionFilter(
        "Season",
        listOf(
            Option("Any", null),
            Option("Winter", "WINTER"),
            Option("Spring", "SPRING"),
            Option("Summer", "SUMMER"),
            Option("Fall", "FALL"),
        ),
    )

    class YearFilter : AnimeFilter.Select<String>(
        "Year",
        buildList {
            add("Any")
            val maxYear = Calendar.getInstance().get(Calendar.YEAR) + 1
            for (year in maxYear downTo 1940) add(year.toString())
        }.toTypedArray(),
    ) {
        val selected: Int?
            get() = if (state == 0) null else values.getOrNull(state)?.toIntOrNull()
    }

    class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name)

    class GenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Genres",
        GENRES.map { GenreCheckBox(it) },
    ) {
        val selected: List<String>
            get() = state.filter { it.state }.map { it.name }
    }

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        FormatFilter(),
        StatusFilter(),
        SeasonFilter(),
        YearFilter(),
        GenreFilter(),
    )

    /** AniList genre names as accepted by `BrowseAnime`. */
    val GENRES = listOf(
        "Action",
        "Adventure",
        "Comedy",
        "Drama",
        "Ecchi",
        "Fantasy",
        "Horror",
        "Mahou Shoujo",
        "Mecha",
        "Music",
        "Mystery",
        "Psychological",
        "Romance",
        "Sci-Fi",
        "Slice of Life",
        "Sports",
        "Supernatural",
        "Thriller",
    )
}
