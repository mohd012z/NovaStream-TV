package com.novastream.tv

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object EpgParser {
    fun parse(raw: String): List<EpgProgramme> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(raw))
        val out = mutableListOf<EpgProgramme>()
        var event = parser.eventType
        var channel = ""
        var start = 0L
        var stop = 0L
        var title = ""
        var desc = ""
        var insideProgramme = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        insideProgramme = true
                        channel = parser.getAttributeValue(null, "channel").orEmpty()
                        start = parseXmlTvTime(parser.getAttributeValue(null, "start"))
                        stop = parseXmlTvTime(parser.getAttributeValue(null, "stop"))
                        title = ""; desc = ""
                    }
                    "title" -> if (insideProgramme) title = parser.nextText().trim()
                    "desc" -> if (insideProgramme) desc = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme" && insideProgramme) {
                    if (channel.isNotBlank() && title.isNotBlank() && start > 0 && stop > start) {
                        out += EpgProgramme(channel, title, desc, start, stop)
                    }
                    insideProgramme = false
                }
            }
            event = parser.next()
        }
        return out
    }

    fun now(programmes: List<EpgProgramme>, channelId: String?, nowMs: Long = System.currentTimeMillis()): EpgProgramme? {
        if (channelId.isNullOrBlank()) return null
        return programmes.firstOrNull { it.channelId == channelId && nowMs in it.startMs until it.stopMs }
    }

    private fun parseXmlTvTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val trimmed = value.trim()
        val patterns = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z", "yyyyMMddHHmmss", "yyyyMMddHHmm")
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (!pattern.contains("Z")) fmt.timeZone = TimeZone.getDefault()
                return fmt.parse(trimmed)?.time ?: 0L
            } catch (_: Exception) { }
        }
        return 0L
    }
}
