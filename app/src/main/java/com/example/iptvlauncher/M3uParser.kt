package com.example.iptvlauncher

data class TvChannel(
    val name: String,
    val url: String,
    val group: String = "Без категории",
    val logo: String? = null,
    val tvgId: String? = null
)

object M3uParser {
    fun parse(text: String): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
        var name = ""
        var group = "Без категории"
        var logo: String? = null
        var tvgId: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfterLast(",").trim().ifBlank { "Канал ${result.size + 1}" }
                group = attr(line, "group-title") ?: "Без категории"
                logo = attr(line, "tvg-logo")
                tvgId = attr(line, "tvg-id")
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                result += TvChannel(name, line, group, logo, tvgId)
                name = ""
                group = "Без категории"
                logo = null
                tvgId = null
            }
        }
        return result
    }

    private fun attr(line: String, key: String): String? {
        val regex = Regex("""$key\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)
    }
}
