package app.iduanju.core.novel

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class NovelParsed(
    val title: String,
    val author: String?,
    val text: String,
    val chapters: List<Chapter>,
) {
    data class Chapter(val title: String, val start: Int, val end: Int)
}

/** Local TXT decoder and chapter splitter, matching the iOS NovelImport behavior. */
object NovelImport {
    private val heading = Regex("""^(?:第[0-9零〇一二两三四五六七八九十百千万]+[章回节卷集部篇](?:[ 　:：·、.\-—].{0,30}|.{0,30})|(?:chapter)\s*\d+.{0,30}|序章.{0,20}|序言|前言|楔子.{0,20}|引子.{0,20}|尾声.{0,20}|后记|番外.{0,24})$""", RegexOption.IGNORE_CASE)

    fun parse(data: ByteArray, fileName: String): NovelParsed {
        require(data.isNotEmpty()) { "TXT 文件为空" }
        require(data.size <= 20 * 1024 * 1024) { "TXT 文件超过 20 MB" }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        require(ext in setOf("txt", "text", "")) { "不支持的格式：$ext（本地书籍只支持 TXT）" }
        val title = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').ifBlank { "本地小说" }
        val normalized = normalize(decode(data))
        require(normalized.isNotBlank()) { "TXT 文件没有正文" }
        return NovelParsed(title, null, normalized, splitChapters(normalized))
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return strict(bytes.copyOfRange(3, bytes.size), Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return strict(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE).trimStart('\uFEFF')
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return strict(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE).trimStart('\uFEFF')
        for (charset in listOf(Charsets.UTF_8, Charset.forName("GB18030"), Charset.forName("Big5"))) {
            try { return strict(bytes, charset) } catch (_: CharacterCodingException) { }
        }
        throw IllegalArgumentException("无法识别文件编码（支持 UTF-8 / GB18030 / UTF-16）")
    }

    fun normalize(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n')
        .split('\n').map { it.trim().trim('\u3000', '\u00A0') }.filter { it.isNotEmpty() }.joinToString("\n")

    fun splitChapters(text: String): List<NovelParsed.Chapter> {
        val lines = text.split('\n')
        val starts = arrayListOf<Pair<String, Int>>()
        var offset = 0
        for (line in lines) {
            if (line.length <= 40 && heading.matches(line)) starts += line to offset
            offset += line.length + 1
        }
        if (starts.isNotEmpty()) {
            val out = arrayListOf<NovelParsed.Chapter>()
            val first = starts.first()
            if (first.second > 0 && text.substring(0, first.second).trim().length > 80) out += NovelParsed.Chapter("前言", 0, first.second)
            starts.forEachIndexed { i, (name, start) ->
                val from = if (i == 0 && out.isEmpty()) 0 else start
                val end = starts.getOrNull(i + 1)?.second ?: text.length
                if (end > from) out += NovelParsed.Chapter(name, from, end)
            }
            return out
        }
        val out = arrayListOf<NovelParsed.Chapter>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + 8000)
            if (end < text.length) {
                val newline = text.indexOf('\n', end).takeIf { it >= 0 && it - end <= 2000 }
                if (newline != null) end = newline + 1
            }
            out += NovelParsed.Chapter("第 ${out.size + 1} 节", start, end)
            start = end
        }
        return out
    }

    private fun strict(bytes: ByteArray, charset: Charset): String = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
