package app.iduanju.novel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.core.novel.*
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
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
    var downloadBusy by remember { mutableStateOf(false) }
    var downloadDone by remember { mutableIntStateOf(0) }
    var downloadTotal by remember { mutableIntStateOf(0) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var cachedCount by remember { mutableIntStateOf(0) }
    var prefetch by remember { mutableIntStateOf(prefs.getInt("prefetch",3)) }
    var showDownload by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var alternatives by remember { mutableStateOf<List<Pair<NovelSource,NovelHit>>>(emptyList()) }
    var switchBusy by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf("") }
    val cache = remember { NovelCache(File(context.filesDir,"novel-cache-v2")) }
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
    suspend fun read(index: Int, selectedSource: NovelSource? = source, bookToSave: NovelHit? = currentBook) {
        val s = selectedSource ?: return
        val chapter = chapters.getOrNull(index) ?: return
        val book = bookToSave ?: return
        val cached = withContext(Dispatchers.IO) { cache.read(s.id, book.url, chapter.url) }
        val body = cached ?: withContext(Dispatchers.IO) { NovelSources.content(s, chapter, chapters.getOrNull(index + 1)?.url) }.also { value ->
            withContext(Dispatchers.IO) { cache.save(s.id,book.url,chapter.url,value) }
        }
        text = body; chapterIndex = index; localBook = false
        bookToSave?.let { book ->
            prefs.edit().putString("last", JSONObject().put("source", s.id).put("name", book.name).put("author", book.author).put("url", book.url).put("chapter", index).toString()).apply()
        }
        readerState.scrollToItem(0)
        val toc = chapters.toList()
        cachedCount = withContext(Dispatchers.IO) { cache.count(s.id,book.url,toc) }
        if (prefetch > 0) scope.launch(Dispatchers.IO) {
            for(i in index+1 until minOf(toc.size,index+1+prefetch)) {
                if(cache.read(s.id,book.url,toc[i].url)==null) {
                    try { cache.save(s.id,book.url,toc[i].url,NovelSources.content(s,toc[i],toc.getOrNull(i+1)?.url)) }
                    catch(e: CancellationException){throw e} catch(_:Exception){break}
                }
            }
        }
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
        if (source != null) try {
            categories = withContext(Dispatchers.IO) { NovelSources.categories(source) }
            selectedGroup = categories.firstOrNull()?.group.orEmpty()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    fun download(count: Int?) {
        val book = currentBook ?: return; val s = source ?: return; val toc = chapters.toList()
        if(downloadBusy)return
        val start=if(count==null)0 else chapterIndex.coerceAtLeast(0)
        val end=if(count==null)toc.size else minOf(toc.size,start+count)
        downloadBusy=true;downloadDone=0;downloadTotal=end-start
        downloadJob=scope.launch {
            try {
                withContext(Dispatchers.IO) { cache.saveBook(s.id,book,toc) }
                for(i in start until end) {
                    withContext(Dispatchers.IO) {
                        if(cache.read(s.id,book.url,toc[i].url)==null) cache.save(s.id,book.url,toc[i].url,NovelSources.content(s,toc[i],toc.getOrNull(i+1)?.url))
                    }
                    downloadDone++;cachedCount=withContext(Dispatchers.IO){cache.count(s.id,book.url,toc)}
                }
            } catch(e:CancellationException){error="下载已暂停，可继续";throw e} catch(e:Exception){error="下载中断：${e.message}，已完成章节保留"}
            finally { downloadBusy=false }
        }
    }
    fun findSources() {
        val target=currentBook ?: return
        showSwitch=true
        if(switchBusy)return
        switchBusy=true;alternatives=emptyList()
        scope.launch {
            try {
                for(s in sources.filter{it.enabled && it.id!=selected}) {
                    val matches=withContext(Dispatchers.IO){runCatching{NovelSources.search(s,target.name)}.getOrDefault(emptyList())}
                        .filter{it.name==target.name && (it.author==target.author || it.author.isBlank() || target.author.isBlank())}
                    alternatives=alternatives+matches.map{s to it}
                }
            } finally {switchBusy=false}
        }
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
            if(!localBook) Row {
                TextButton(onClick={showDownload=true}){Text("缓存 / 下载")}
                TextButton(onClick={findSources()}){Text("换源")}
                Text("已缓存 $cachedCount 章",color=IdjThemeColors.secondary,fontSize=12.sp,modifier=Modifier.padding(12.dp))
            }
            val lines = remember(text) { text.lines().filter { it.isNotBlank() }.flatMap { it.chunked(2000) } }
            LazyColumn(state = readerState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 30.dp)) {
                items(lines.size) { i -> Text(lines[i], color = IdjThemeColors.text, fontSize = font.sp, lineHeight = (font * 1.8).sp, modifier = Modifier.padding(vertical = 6.dp)) }
            }
        } else if (currentBook != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(currentBook!!.name, color = IdjThemeColors.text, modifier = Modifier.weight(1f))
                Text("已缓存 $cachedCount/${chapters.size}", color = IdjThemeColors.secondary, fontSize = 12.sp)
            }
            if (downloadBusy) {
                LinearProgressIndicator(progress = { if (downloadTotal == 0) 0f else downloadDone.toFloat() / downloadTotal }, modifier = Modifier.fillMaxWidth())
                Text("正在缓存 $downloadDone / $downloadTotal 章", color = IdjThemeColors.secondary, fontSize = 12.sp)
            } else {
                Row { TextButton(enabled = !busy && chapters.isNotEmpty(), onClick = {showDownload=true}) { Text("缓存 / 下载") }
                    TextButton(onClick={findSources()}){Text("换源")}}
            }
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
                        val book = NovelHit(last.getString("name"), last.optString("author"), last.getString("url"))
                        selected = s.id; currentBook = book
                        chapters = withContext(Dispatchers.IO) { cache.books().firstOrNull{it.source==s.id&&it.book.url==book.url}?.chapters ?: NovelSources.chapters(s,book.url) }
                        if (chapters.isNotEmpty()) read(last.optInt("chapter").coerceIn(0, chapters.lastIndex), s, book)
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
            if(categories.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                categories.map{it.group}.distinct().forEach { group ->
                    TextButton(onClick={selectedGroup=group}){Text(group,fontWeight=if(group==selectedGroup)FontWeight.Bold else FontWeight.Normal)}
                }
            }
            Row(Modifier.weight(1f)) {
            if(categories.isNotEmpty()) LazyColumn(Modifier.width(92.dp).fillMaxHeight().background(IdjThemeColors.elevated)) {
                items(categories.filter{it.group==selectedGroup},key={it.id}) { cat ->
                    Text(cat.title,color=if(cat==category)IdjThemeColors.accentWarm else IdjThemeColors.text,fontSize=14.sp,
                        fontWeight=if(cat==category)FontWeight.Bold else FontWeight.Normal,
                        modifier=Modifier.fillMaxWidth().clickable(enabled=!busy) {
                            if(cat.action!=null)work{withContext(Dispatchers.IO){NovelSources.categoryAction(source!!,cat)};categories=withContext(Dispatchers.IO){NovelSources.categories(source!!)}}
                            else search(true,cat)
                        }.padding(horizontal=10.dp,vertical=18.dp))
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(start=12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (hits.isEmpty()) {
                    item { Text("发现 · 书源推荐与榜单", color = IdjThemeColors.text, modifier = Modifier.padding(vertical = 12.dp)) }
                    item { Text(if(categories.isEmpty())"此书源没有发现栏目，可搜索或导入其他书源。" else "选择左侧栏目，查看书源实时榜单", color = IdjThemeColors.secondary) }
                    items(cache.books(),key={it.source+it.book.url}) { saved ->
                        Text("离线 · ${saved.book.name}",color=IdjThemeColors.text,modifier=Modifier.fillMaxWidth().clickable{
                            selected=saved.source;currentBook=saved.book;chapters=saved.chapters;cachedCount=cache.count(saved.source,saved.book.url,saved.chapters)
                        }.padding(vertical=12.dp))
                    }
                } else {
                    item { TextButton(onClick = { hits = emptyList(); more = false }) { Text("返回发现栏目") } }
                    items(hits, key = { it.url }) { book ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !busy) { work {
                            val s = source ?: error("请先选择书源")
                            val result = withContext(Dispatchers.IO) { NovelSources.chapters(s, book.url) }
                            withContext(Dispatchers.IO){cache.saveBook(s.id,book,result)}
                            currentBook = book; chapters = result;cachedCount=cache.count(s.id,book.url,result)
                        } }.padding(vertical = 12.dp)) {
                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                coil.compose.AsyncImage(book.cover,null,Modifier.size(58.dp,80.dp).background(IdjThemeColors.elevated),contentScale=ContentScale.Crop)
                                Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                    Text("${hits.indexOf(book)+1}. ${book.name}",color=IdjThemeColors.text,fontSize=17.sp,fontWeight=FontWeight.SemiBold,maxLines=2)
                                    Text(listOf(book.author,book.kind).filter{it.isNotBlank()}.joinToString(" · "),color=IdjThemeColors.secondary,fontSize=12.sp,maxLines=2)
                                    if(book.latest.isNotBlank())Text(book.latest,color=IdjThemeColors.secondary,fontSize=11.sp,maxLines=1)
                                }
                            }
                        }
                    }
                    if (more) item { TextButton(onClick = { search(false) }, enabled = !busy) { Text("加载更多") } }
                }
            }
            }
        }
    }
    if(showDownload) AlertDialog(onDismissRequest={showDownload=false},title={Text("缓存与下载")},text={Column{
        Text("已缓存 $cachedCount / ${chapters.size} 章")
        if(downloadBusy){Text("本次 $downloadDone / $downloadTotal");TextButton(onClick={downloadJob?.cancel()}){Text("暂停")}}
        else {TextButton(onClick={download(50)}){Text("缓存后 50 章")};TextButton(onClick={download(200)}){Text("缓存后 200 章")};TextButton(onClick={download(null)}){Text("下载全本 / 继续")}}
        Text("提前缓存")
        Row {listOf(0,3,10,20).forEach{n->TextButton(onClick={prefetch=n;prefs.edit().putInt("prefetch",n).apply()}){Text(if(n==0)"关" else "$n 章",fontWeight=if(prefetch==n)FontWeight.Bold else FontWeight.Normal)}}}
        Text("保持应用前台，已完成章节可离线阅读。",fontSize=12.sp)
    }},confirmButton={TextButton(onClick={showDownload=false}){Text("完成")}})
    if(showSwitch)AlertDialog(onDismissRequest={showSwitch=false},title={Text("切换书籍来源")},text={LazyColumn{
        if(switchBusy)item{Text("正在搜索同名书籍…")}
        items(alternatives,key={it.first.id+it.second.url}){(s,book)->TextButton(onClick={work{
            val oldTitle=chapters.getOrNull(chapterIndex)?.title
            val toc=withContext(Dispatchers.IO){NovelSources.chapters(s,book.url)}
            require(toc.isNotEmpty()){"该源目录为空"}
            val match=toc.indexOfFirst{it.title.replace(Regex("\\s+"),"")==oldTitle?.replace(Regex("\\s+"),"")}.takeIf{it>=0} ?: chapterIndex.coerceIn(0,toc.lastIndex)
            withContext(Dispatchers.IO){cache.saveBook(s.id,book,toc)}
            selected=s.id;currentBook=book;chapters=toc;showSwitch=false;read(match,s,book)
        }}){Text(s.name)}}
        if(!switchBusy&&alternatives.isEmpty())item{Text("未找到同名同作者书籍，可启用更多书源后重试")}
    }},confirmButton={TextButton(onClick={showSwitch=false}){Text("关闭")}})
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
