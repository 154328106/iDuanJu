package app.iduanju.novel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.core.novel.*
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Imported sources and reading progress are private to this installation. */
@Composable
fun NovelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("novel", 0) }
    val sources = remember { mutableStateListOf<NovelSource>().apply { addAll(runCatching { NovelSources.import(prefs.getString("sources", "[]")!!) }.getOrDefault(emptyList())) } }
    var selected by remember { mutableStateOf(sources.firstOrNull()?.id ?: "") }
    var query by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<NovelHit>>(emptyList()) }
    var categories by remember { mutableStateOf<List<NovelCategory>>(emptyList()) }
    var category by remember { mutableStateOf<NovelCategory?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf<List<NovelChapter>>(emptyList()) }
    var currentBook by remember { mutableStateOf<NovelHit?>(null) }
    var chapterIndex by remember { mutableIntStateOf(-1) }
    var text by remember { mutableStateOf("") }
    var localBook by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var font by remember { mutableFloatStateOf(prefs.getFloat("font", 20f)) }
    val scope = rememberCoroutineScope()
    val readerState = rememberLazyListState()
    val source = sources.firstOrNull { it.id == selected }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    fun saveSources() { prefs.edit().putString("sources", JSONArray(sources.map { it.json }).toString()).apply() }
    fun addSources(raw: String) {
        val parsed = NovelSources.import(raw)
        require(parsed.isNotEmpty()) { "没有可用的文本书源" }
        for (s in parsed) { sources.removeAll { it.id == s.id }; sources.add(s) }
        saveSources(); selected = parsed.first().id; importing = false
    }
    suspend fun read(index: Int, selectedSource: NovelSource? = source) {
        val s = selectedSource ?: return
        val chapter = chapters.getOrNull(index) ?: return
        val body = withContext(Dispatchers.IO) { NovelSources.content(s, chapter, chapters.getOrNull(index + 1)?.url) }
        text = body; chapterIndex = index; localBook = false
        currentBook?.let { book ->
            prefs.edit().putString("last", JSONObject().put("source", s.id).put("name", book.name).put("author", book.author).put("url", book.url).put("chapter", index).toString()).apply()
        }
        readerState.scrollToItem(0)
    }
    fun search(reset: Boolean, cat: NovelCategory? = null) = work {
        val s = source ?: error("请先导入并选择书源")
        val nextPage = if (reset) 1 else page
        val chosen = if (reset) cat else category
        val result = withContext(Dispatchers.IO) { NovelSources.search(s, query, nextPage, chosen) }
        val existing = if (reset) emptyList() else hits
        val fresh = result.filter { r -> existing.none { it.url == r.url } }
        hits = existing + fresh; page = nextPage + 1; more = fresh.isNotEmpty(); category = chosen
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val raw = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break; require(out.size() + n <= 20 * 1024 * 1024) { "文件超过 20 MB" }; out.write(buffer, 0, n) }
                val bytes = out.toByteArray()
                val utf = bytes.toString(Charsets.UTF_8)
                if (bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) || (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))) bytes.toString(Charsets.UTF_16)
                else if ('\uFFFD' in utf) bytes.toString(java.nio.charset.Charset.forName("GB18030")) else utf
            } ?: error("无法读取文件") }
            if (raw.trimStart().startsWith('[') || raw.trimStart().startsWith('{')) addSources(raw)
            else {
                withContext(Dispatchers.IO) { File(context.filesDir, "novel-local.txt").writeText(raw) }
                text = raw; localBook = true; chapterIndex = -1; currentBook = null
            }
        }
    }
    LaunchedEffect(selected) {
        hits = emptyList(); categories = emptyList(); more = false; error = null
        if (source != null) try { categories = NovelSources.categories(source) } catch (e: Exception) { error = e.message }
    }
    fun back() { if (text.isNotEmpty()) text = "" else if (currentBook != null) { currentBook = null; chapters = emptyList() } else onBack() }
    androidx.activity.compose.BackHandler { back() }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { back() }) { Text("返回") }
            Text(if (text.isNotEmpty()) chapters.getOrNull(chapterIndex)?.title ?: "本地小说" else "小说", color = IdjThemeColors.text, modifier = Modifier.weight(1f).padding(12.dp), maxLines = 1)
            TextButton(onClick = { importing = true }, enabled = !busy) { Text("书源") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = IdjThemeColors.accentWarm) }
        if (text.isNotEmpty()) {
            Row {
                TextButton(onClick = { font = (font - 1).coerceAtLeast(14f); prefs.edit().putFloat("font", font).apply() }) { Text("A−") }
                TextButton(onClick = { font = (font + 1).coerceAtMost(32f); prefs.edit().putFloat("font", font).apply() }) { Text("A+") }
                if (!localBook) {
                    TextButton(onClick = { work { read(chapterIndex - 1) } }, enabled = chapterIndex > 0 && !busy) { Text("上一章") }
                    TextButton(onClick = { work { read(chapterIndex + 1) } }, enabled = chapterIndex + 1 < chapters.size && !busy) { Text("下一章") }
                }
            }
            val lines = remember(text) { text.lines().filter { it.isNotBlank() }.flatMap { it.chunked(2000) } }
            LazyColumn(state = readerState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 30.dp)) {
                items(lines.size) { i -> Text(lines[i], color = IdjThemeColors.text, fontSize = font.sp, lineHeight = (font * 1.8).sp, modifier = Modifier.padding(vertical = 6.dp)) }
            }
        } else if (currentBook != null) {
            Text(currentBook!!.name, color = IdjThemeColors.text)
            LazyColumn(Modifier.weight(1f)) {
                items(chapters.size) { i -> Text(chapters[i].title, color = IdjThemeColors.text, modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { work { read(i) } }.padding(14.dp)) }
                if (!busy && chapters.isEmpty()) item { Text("目录为空，请检查书源规则", color = IdjThemeColors.secondary) }
            }
        } else {
            Row {
                TextButton(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }, enabled = !busy) { Text("导入 TXT / 书源") }
                TextButton(onClick = { work {
                    val last = prefs.getString("last", null)?.let { JSONObject(it) }
                    if (last == null) { text = withContext(Dispatchers.IO) { File(context.filesDir, "novel-local.txt").readText() }; localBook = true }
                    else {
                        val s = sources.firstOrNull { it.id == last.optString("source") } ?: error("续读书源已删除")
                        selected = s.id; currentBook = NovelHit(last.getString("name"), last.optString("author"), last.getString("url"))
                        chapters = withContext(Dispatchers.IO) { NovelSources.chapters(s, last.getString("url")) }
                        if (chapters.isNotEmpty()) read(last.optInt("chapter").coerceIn(0, chapters.lastIndex), s)
                    }
                } }, enabled = !busy) { Text("继续阅读") }
            }
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }, enabled = !busy) { Text(source?.name ?: "选择书源") }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    sources.filter { it.enabled }.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { selected = s.id; expanded = false }) }
                }
            }
            Row { OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("书名 / 作者") }, singleLine = true)
                TextButton(onClick = { search(true) }, enabled = !busy && query.isNotBlank()) { Text("搜索") } }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (hits.isEmpty()) {
                    item { Text("发现 · 书源推荐与榜单", color = IdjThemeColors.text, modifier = Modifier.padding(vertical = 12.dp)) }
                    items(categories, key = { it.url }) { cat -> Text(cat.title, color = IdjThemeColors.accent, modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { search(true, cat) }.padding(12.dp)) }
                    if (categories.isEmpty()) item { Text("此书源没有发现栏目，可搜索或导入带 exploreUrl 的书源。支持 CSS/Jsoup 与简单 JSONPath；复杂 JS/XPath 书源暂不支持。", color = IdjThemeColors.secondary) }
                } else {
                    item { TextButton(onClick = { hits = emptyList(); more = false }) { Text("返回发现栏目") } }
                    items(hits, key = { it.url }) { book ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !busy) { work {
                            val s = source ?: error("请先选择书源")
                            val result = withContext(Dispatchers.IO) { NovelSources.chapters(s, book.url) }
                            currentBook = book; chapters = result
                        } }.padding(vertical = 12.dp)) {
                            Text(book.name, color = IdjThemeColors.text); Text(book.author, color = IdjThemeColors.secondary)
                        }
                    }
                    if (more) item { TextButton(onClick = { search(false) }, enabled = !busy) { Text("加载更多") } }
                }
            }
        }
    }
    if (importing) AlertDialog(onDismissRequest = { importing = false }, title = { Text("导入阅读书源") },
        text = { Column { Text("粘贴 Legado JSON 或书源订阅 URL")
            OutlinedTextField(importText, { importText = it }, maxLines = 8)
            if (source != null) TextButton(onClick = { sources.removeAll { it.id == selected }; saveSources(); selected = sources.firstOrNull()?.id ?: "" }) { Text("删除当前书源") }
        } },
        confirmButton = { TextButton(onClick = { work {
            val raw = importText.trim()
            val data = if (raw.startsWith("https://") || raw.startsWith("http://")) withContext(Dispatchers.IO) { NovelSources.fetch(raw, NovelSource(JSONObject().put("bookSourceUrl", raw))) } else raw
            addSources(data)
        } }, enabled = !busy) { Text("导入") } }, dismissButton = { TextButton(onClick = { importing = false }) { Text("取消") } })
}
