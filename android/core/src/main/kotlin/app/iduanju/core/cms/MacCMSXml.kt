package app.iduanju.core.cms

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** Project MacCMS RSS into the same records used by the JSON provider. */
internal object MacCMSXml {
    private val fields = mapOf("id" to "vod_id", "name" to "vod_name", "pic" to "vod_pic", "type" to "type_name",
        "last" to "vod_time", "note" to "vod_remarks", "actor" to "vod_actor", "director" to "vod_director",
        "des" to "vod_content", "year" to "vod_year", "area" to "vod_area", "tid" to "type_id")

    fun parse(body: String): JSONObject {
        require(!body.contains("<!DOCTYPE", ignoreCase = true))
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(body))
        val result = JSONObject()
        val videos = JSONArray()
        val classes = JSONArray()
        var video: JSONObject? = null
        val flags = mutableListOf<String>()
        val urls = mutableListOf<String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (parser.depth == 1) require(tag == "rss")
                    when (tag) {
                        "list" -> { result.put("list", videos); result.put("pagecount", parser.getAttributeValue(null, "pagecount")) }
                        "class" -> result.put("class", classes)
                        "ty" -> { val id = parser.getAttributeValue(null, "id"); classes.put(JSONObject().put("type_id", id).put("type_name", parser.nextText())) }
                        "video" -> { video = JSONObject(); flags.clear(); urls.clear() }
                        "dd" -> if (video != null) { flags += parser.getAttributeValue(null, "flag") ?: "线路${flags.size + 1}"; urls += parser.nextText() }
                        else -> fields[tag]?.let { key -> video?.put(key, parser.nextText()) }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "video" && video != null) {
                    videos.put(video.put("vod_play_from", flags.joinToString("$$$")).put("vod_play_url", urls.joinToString("$$$")))
                    video = null
                }
            }
        }
        require(result.has("class") || result.has("list"))
        return result
    }
}
