package com.example.iptvlauncher

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.io.InputStream

data class EpgProgramme(
    val channelId: String,
    val channelNames: List<String>,
    val title: String,
    val startMillis: Long,
    val endMillis: Long
) {
    fun timeRange(): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${formatter.format(startMillis)}–${formatter.format(endMillis)}"
    }
}

object EpgParser {
    fun parse(xml: InputStream): List<EpgProgramme> {
        val parser = Xml.newPullParser()
        parser.setInput(xml, null)
        val names = mutableMapOf<String, MutableList<String>>()
        val result = mutableListOf<EpgProgramme>()
        var event = ""
        var channelId = ""
        var start = 0L
        var stop = 0L
        var title = ""
        var inDisplayName = false
        var inTitle = false
        var displayName = StringBuilder()
        var titleText = StringBuilder()
        var depth = 0
        // Ограничение защищает приставки от EPG с огромным числом передач.
        while (parser.eventType != XmlPullParser.END_DOCUMENT && result.size < 50000) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "channel" -> {
                            event = "channel"
                            channelId = parser.getAttributeValue(null, "id") ?: ""
                        }
                        "programme" -> {
                            event = "programme"
                            channelId = parser.getAttributeValue(null, "channel") ?: ""
                            start = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            title = ""
                        }
                        "display-name" -> if (event == "channel") {
                            inDisplayName = true
                            displayName = StringBuilder()
                        }
                        "title" -> if (event == "programme") {
                            inTitle = true
                            titleText = StringBuilder()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inDisplayName) displayName.append(parser.text)
                    if (inTitle) titleText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "display-name" -> if (inDisplayName) {
                            names.getOrPut(channelId) { mutableListOf() }.add(displayName.toString().trim())
                            inDisplayName = false
                        }
                        "title" -> if (inTitle) {
                            title = titleText.toString().trim()
                            inTitle = false
                        }
                        "channel" -> event = ""
                        "programme" -> {
                            if (channelId.isNotBlank() && title.isNotBlank() && start > 0L && stop > start) {
                                result += EpgProgramme(channelId, names[channelId]?.toList().orEmpty(), title, start, stop)
                            }
                            event = ""
                        }
                    }
                    depth--
                }
            }
            parser.next()
        }
        return result
    }

    private fun parseXmltvTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val text = value.trim()
        val patterns = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z", "yyyyMMddHHmmss", "yyyyMMddHHmm")
        for (pattern in patterns) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    if (!pattern.contains("Z")) timeZone = TimeZone.getDefault()
                }
                val parsed = formatter.parse(text) ?: continue
                return parsed.time
            } catch (_: Exception) { }
        }
        return 0L
    }
}
