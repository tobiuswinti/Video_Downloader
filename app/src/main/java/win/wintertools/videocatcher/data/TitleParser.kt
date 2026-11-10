package win.wintertools.videocatcher.data

/**
 * Erkennt aus einem Titel oder Dateinamen, ob es eine Serienfolge ist:
 * "Name S01E02", "Name 1x02", "Name Staffel 1 Folge 2", "Name Folge 5".
 */
object TitleParser {

    data class Parsed(val series: String?, val season: Int?, val episode: Int?)

    private val none = Parsed(null, null, null)

    private val patterns = listOf(
        // S01E02, s1 e2, S01.E02
        Regex("(?i)(?<![A-Za-z0-9])S(\\d{1,2})[ ._-]?E(\\d{1,3})(?![0-9])") to true,
        // Staffel 1 Folge 2, Season 1 Episode 2
        Regex("(?i)(?:Staffel|Season)[ ._]*(\\d{1,2})\\D{0,12}?(?:Folge|Episode|Ep)[ ._]*(\\d{1,3})") to true,
        // 1x02
        Regex("(?<![A-Za-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])") to true,
        // Folge 5, Episode 5 (ohne Staffel -> Staffel 1)
        Regex("(?i)(?<![A-Za-z])(?:Folge|Episode|Ep\\.?)[ ._]*(\\d{1,3})(?![0-9])") to false
    )

    fun parse(title: String): Parsed {
        for ((regex, hasSeason) in patterns) {
            val m = regex.find(title) ?: continue
            val season = if (hasSeason) m.groupValues[1].toIntOrNull() else 1
            val episode = m.groupValues[if (hasSeason) 2 else 1].toIntOrNull()
            if (season == null || episode == null) continue
            val name = cleanName(title.substring(0, m.range.first))
            if (name.isEmpty()) continue
            return Parsed(name, season, episode)
        }
        return none
    }

    private fun cleanName(raw: String): String =
        raw.replace('.', ' ').replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('-', '–', '—', '|', ':', ',', '(', '[', ' ')
            .trim()
}
