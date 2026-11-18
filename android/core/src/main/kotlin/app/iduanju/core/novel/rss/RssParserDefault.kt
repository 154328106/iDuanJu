/* Derived from Luoyacheng/legado-E (Legado contributors), GPL-3.0.
 * Upstream 8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c: app/src/main/java/io/legado/app/model/rss/RssParserDefault.kt
 * Modified 2026-11-18: package/data model substitution, remove Android debug logger,
 * reject DTD/entities before parsing. Original parser logic retained.
 * License and provenance: third_party/legado-E/LICENSE and UPSTREAM-PORT.md.
 */
package app.iduanju.core.novel.rss

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader

@Suppress("unused")
object RssParserDefault {

    @Throws(XmlPullParserException::class, IOException::class)
    fun parseXML(
        sortName: String,
        xml: String,
        sourceUrl: String
    ): Pair<MutableList<RssArticle>, String?> {

        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "RSS 不允许外部实体/DTD" }
        val articleList = mutableListOf<RssArticle>()
        var currentArticle = RssArticle()

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false

        val xmlPullParser = factory.newPullParser()
        xmlPullParser.setInput(StringReader(xml))

        // A flag just to be sure of the correct parsing
        var insideItem = false

        var eventType = xmlPullParser.eventType

        // Start parsing the xml
        loop@ while (eventType != XmlPullParser.END_DOCUMENT) {

            // Start parsing the item
            if (eventType == XmlPullParser.START_TAG) {
                when {
                    xmlPullParser.name.equals(RSS_ITEM, true) ->
                        insideItem = true
                    xmlPullParser.name.equals(RSS_ITEM_TITLE, true) ->
                        if (insideItem) currentArticle.title = xmlPullParser.nextText().trim()
                    xmlPullParser.name.equals(RSS_ITEM_LINK, true) ->
                        if (insideItem) currentArticle.link = xmlPullParser.nextText().trim()
                    xmlPullParser.name.equals(RSS_ITEM_THUMBNAIL, true) ->
                        if (insideItem) currentArticle.image =
                            xmlPullParser.getAttributeValue(null, RSS_ITEM_URL)
                    xmlPullParser.name.equals(RSS_ITEM_ENCLOSURE, true) ->
                        if (insideItem) {
                            val type =
                                xmlPullParser.getAttributeValue(null, RSS_ITEM_TYPE)
                            if (type != null && type.contains("image/")) {
                                currentArticle.image =
                                    xmlPullParser.getAttributeValue(null, RSS_ITEM_URL)
                            }
                        }
                    xmlPullParser.name
                        .equals(RSS_ITEM_DESCRIPTION, true) ->
                        if (insideItem) {
                            val description = xmlPullParser.nextText()
                            currentArticle.description = description.trim()
                            if (currentArticle.image == null) {
                                currentArticle.image = getImageUrl(description)
                            }
                        }
                    xmlPullParser.name.equals(RSS_ITEM_CONTENT, true) ->
                        if (insideItem) {
                            val content = xmlPullParser.nextText().trim()
                            currentArticle.content = content
                            if (currentArticle.image == null) {
                                currentArticle.image = getImageUrl(content)
                            }
                        }
                    xmlPullParser.name
                        .equals(RSS_ITEM_PUB_DATE, true) ->
                        if (insideItem) {
                            val nextTokenType = xmlPullParser.next()
                            if (nextTokenType == XmlPullParser.TEXT) {
                                currentArticle.pubDate = xmlPullParser.text.trim()
                            }
                            // Skip to be able to find date inside 'tag' tag
                            continue@loop
                        }
                    xmlPullParser.name.equals(RSS_ITEM_TIME, true) ->
                        if (insideItem) currentArticle.pubDate = xmlPullParser.nextText()
                }
            } else if (eventType == XmlPullParser.END_TAG
                && xmlPullParser.name.equals("item", true)
            ) {
                // The item is correctly parsed
                insideItem = false
                currentArticle.origin = sourceUrl
                currentArticle.sort = sortName
                articleList.add(currentArticle)
                currentArticle = RssArticle()
            }
            eventType = xmlPullParser.next()
        }
        return Pair(articleList, null)
    }

    /**
     * Finds the first img tag and get the src as featured image
     *
     * @param input The content in which to search for the tag
     * @return The url, if there is one
     */
    private fun getImageUrl(input: String): String? {

        var url: String? = null
        val patternImg = "(<img [^>]*>)".toPattern()
        val matcherImg = patternImg.matcher(input)
        if (matcherImg.find()) {
            val imgTag = matcherImg.group(1)
            val patternLink = "src\\s*=\\s*\"([^\"]+)\"".toPattern()
            val matcherLink = patternLink.matcher(imgTag!!)
            if (matcherLink.find()) {
                url = matcherLink.group(1)!!.trim()
            }
        }
        return url
    }

    private const val RSS_ITEM = "item"
    private const val RSS_ITEM_TITLE = "title"
    private const val RSS_ITEM_LINK = "link"
    private const val RSS_ITEM_CATEGORY = "category"
    private const val RSS_ITEM_THUMBNAIL = "media:thumbnail"
    private const val RSS_ITEM_ENCLOSURE = "enclosure"
    private const val RSS_ITEM_DESCRIPTION = "description"
    private const val RSS_ITEM_CONTENT = "content:encoded"
    private const val RSS_ITEM_PUB_DATE = "pubDate"
    private const val RSS_ITEM_TIME = "time"
    private const val RSS_ITEM_URL = "url"
    private const val RSS_ITEM_TYPE = "type"
}