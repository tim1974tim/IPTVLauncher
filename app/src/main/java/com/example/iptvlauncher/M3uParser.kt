package com.example.iptvlauncher

data class TvChannel(
    val name: String,
    val url: String,
    val group: String = "Без категории",
    val logo: String? = null,
    val tvgId: String? = null
)

object M3uParser {
    fun parse(text: String, maxChannels: Int = 12000): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
        var name: String? = null
        var group = "Без категории"
        var logo: String? = null
        var tvgId: String? = null

        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            if (result.size >= maxChannels) break
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = findTitleSeparator(line)
                    val info = if (comma >= 0) line.substring(0, comma) else line
                    name = if (comma >= 0) line.substring(comma + 1).trim() else null
                    group = attr(info, "group-title")?.takeIf { it.isNotBlank() } ?: "Без категории"
                    logo = attr(info, "tvg-logo")
                    tvgId = attr(info, "tvg-id")
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    if (line.startsWith("http://", true) || line.startsWith("https://", true) ||
                        line.startsWith("rtmp://", true) || line.startsWith("rtsp://", true)) {
                        result += TvChannel(
                            name = name?.takeIf { it.isNotBlank() } ?: "Канал ${result.size + 1}",
                            url = line,
                            group = group,
                            logo = logo,
                            tvgId = tvgId
                        )
                    }
                    name = null
                    group = "Без категории"
                    logo = null
                    tvgId = null
                }
            }
        }
        return result
    }

    private fun findTitleSeparator(line: String): Int {
        var quoted = false
        var quote = ' '
        for (i in line.indices) {
            val c = line[i]
            if ((c == '"' || c == '\'') && (!quoted || c == quote)) {
                if (quoted) quoted = false else { quoted = true; quote = c }
            } else if (c == ',' && !quoted) return i
        }
        return -1
    }

    private fun attr(line: String, key: String): String? {
        val regex = Regex("""(?:^|\s)$key\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)
    }
}
