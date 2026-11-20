package app.iduanju.core.novel.rss

/** Host-independent projection of upstream RssArticle fields used by the parser. */
data class RssArticle(var title:String="",var link:String="",var pubDate:String="",var description:String?=null,var content:String?=null,var image:String?=null,var origin:String="",var sort:String="")
