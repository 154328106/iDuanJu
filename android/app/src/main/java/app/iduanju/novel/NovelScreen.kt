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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    var switchJob by remember { mutableStateOf<Job?>(null) }
    var readGeneration by remember { mutableIntStateOf(0) }
    var prefetchJob by remember { mutableStateOf<Job?>(null) }
    var selectedGroup by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val cache = remember { NovelCache(File(context.filesDir,"novel-cache-v2")) }
    var font by remember { mutableFloatStateOf(prefs.getFloat("font", 20f)) }
    val scope = rememberCoroutineScope()
    val readerState = rememberLazyListState()
    val source = sources.firstOrNull { it.id == selected }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
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
        val token = ++readGeneration
        prefetchJob?.cancel()
        val cached = withContext(Dispatchers.IO) { cache.read(s.id, book.url, chapter.url) }
        val body = cached ?: withContext(Dispatchers.IO) { NovelSources.content(s, chapter, chapters.getOrNull(index + 1)?.url,book.url) }.also { value ->
            withContext(Dispatchers.IO) { cache.save(s.id,book.url,chapter.url,value) }
        }
        if (token != readGeneration) return
        text = body; chapterIndex = index; localBook = false
        bookToSave?.let { book ->
            prefs.edit().putString("last", JSONObject().put("source", s.id).put("name", book.name).put("author", book.author).put("url", book.url).put("chapter", index).toString()).apply()
        }
        readerState.scrollToItem(0)
        val toc = chapters.toList()
        cachedCount = withContext(Dispatchers.IO) { cache.count(s.id,book.url,toc) }
        if (prefetch > 0) prefetchJob = scope.launch(Dispatchers.IO) {
            for(i in index+1 until minOf(toc.size,index+1+prefetch.coerceIn(0,20))) {
                if(cache.read(s.id,book.url,toc[i].url)==null) {
                    try { cache.save(s.id,book.url,toc[i].url,NovelSources.content(s,toc[i],toc.getOrNull(i+1)?.url,book.url)) }
                    catch(e: CancellationException){throw e} catch(_:Exception){break}
                }
            }
        }
    }
    fun search(reset: Boolean, cat: NovelCategory? = null) = work {
        val s = source ?: error("请先导入并选择书源")
        val nextPage = if (reset) 1 else page
        val chosen = if (reset) cat else category
        if (reset) { hits = emptyList(); more = false; category = chosen }
        val result = withContext(Dispatchers.IO) { NovelSources.search(s, query, nextPage, chosen) }
        if (selected != s.id) return@work
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
        hits = emptyList(); categories = emptyList(); category = null; more = false; error = null
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
                        if(cache.read(s.id,book.url,toc[i].url)==null) cache.save(s.id,book.url,toc[i].url,NovelSources.content(s,toc[i],toc.getOrNull(i+1)?.url,book.url))
                    }
                    downloadDone++
                    if (currentBook?.url == book.url && selected == s.id) cachedCount=withContext(Dispatchers.IO){cache.count(s.id,book.url,toc)}
                }
            } catch(e:CancellationException){error="下载已暂停，可继续";throw e} catch(e:Exception){error="下载中断：${e.message}，已完成章节保留"}
            finally { downloadBusy=false }
        }
    }
    fun findSources() {
        val target=currentBook ?: return
        val selectedSource = selected
        val candidates = sources.filter { it.enabled && it.id != selectedSource && it.json.optString("searchUrl").isNotBlank() }.toList()
        showSwitch=true
        if(switchBusy)return
        switchBusy=true;alternatives=emptyList()
        switchJob=scope.launch {
            try {
                coroutineScope {
                    val gate = Semaphore(4)
                    candidates.forEach { s -> launch {
                        gate.withPermit {
                            val found = try { withContext(Dispatchers.IO) { NovelSources.search(s,target.name) } }
                                catch(e:CancellationException){throw e} catch(_:Exception){emptyList()}
                            val matches=found.filter{it.name==target.name && (it.author==target.author || it.author.isBlank() || target.author.isBlank())}
                            alternatives=(alternatives+matches.map{s to it}).distinctBy { it.first.id+"|"+it.second.url }
                        }
                    } }
                }
            } finally {switchBusy=false}
        }
    }
    fun back() { readGeneration++; prefetchJob?.cancel(); if (text.isNotEmpty()) text = "" else if (currentBook != null) { currentBook = null; chapters = emptyList() } else if(searching) { searching=false;hits=emptyList();category=null;more=false } else onBack() }
    androidx.activity.compose.BackHandler { back() }
    Column(Modifier.fillMaxSize().background(IdjThemeColors.bg).statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp), horizontalArrangement = Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick = { back() }) { Text("返回") }
            Text(if (text.isNotEmpty()) chapters.getOrNull(chapterIndex)?.title ?: "本地小说" else if(currentBook!=null) "书籍详情" else if(searching) "小说搜索" else "小说榜单", fontSize=17.sp,fontWeight=FontWeight.SemiBold,color = IdjThemeColors.text, modifier = Modifier.weight(1f).padding(12.dp), maxLines = 1)
            TextButton(onClick = { importing = true }, enabled = !busy) { Text("书源") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize=13.sp, modifier=Modifier.padding(vertical=8.dp)) }
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
                if (!busy && chapters.isEmpty()) item { Text("这本书没有可读目录，可能已下架、受限或书源失效。可换一本书或切换书源后重试。", color = IdjThemeColors.secondary) }
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
            if(searching) Row(Modifier.padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically) { OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("书名 / 作者") }, singleLine = true)
                TextButton(onClick = { search(true) }, enabled = !busy && query.isNotBlank()) { Text("搜索") } }
            else OutlinedButton(onClick={searching=true;hits=emptyList();more=false;category=null},modifier=Modifier.fillMaxWidth().padding(bottom=12.dp)) {Text("搜索书名或作者")}
            if(!searching && categories.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                categories.map{it.group}.distinct().forEach { group ->
                    TextButton(onClick={
                        selectedGroup=group
                        val first = categories.firstOrNull { it.group == group && it.action == null && it.url.isNotBlank() }
                        if (first != null) search(true, first)
                        else { hits=emptyList();category=null;more=false }
                    }, enabled=!busy){Text(group,fontWeight=if(group==selectedGroup)FontWeight.Bold else FontWeight.Normal)}
                }
            }
            Row(Modifier.weight(1f)) {
            if(!searching && categories.isNotEmpty()) LazyColumn(Modifier.width(92.dp).fillMaxHeight().background(IdjThemeColors.bg)) {
                items(categories.filter{it.group==selectedGroup},key={it.id}) { cat ->
                    Text(cat.title,color=if(cat==category)IdjThemeColors.accent else IdjThemeColors.text,fontSize=14.sp,
                        fontWeight=if(cat==category)FontWeight.Bold else FontWeight.Normal,
                        modifier=Modifier.fillMaxWidth().background(if(cat==category)IdjThemeColors.surface else IdjThemeColors.bg).clickable(enabled=!busy) {
                            if(cat.action!=null)work{withContext(Dispatchers.IO){NovelSources.categoryAction(source!!,cat)};categories=withContext(Dispatchers.IO){NovelSources.categories(source!!)}}
                            else search(true,cat)
                        }.padding(horizontal=10.dp,vertical=18.dp))
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight().background(IdjThemeColors.surface).padding(horizontal=12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (hits.isEmpty()) {
                    item { Text(if(searching)"搜索你的下一本书" else "书源推荐与榜单",fontSize=17.sp,fontWeight=FontWeight.SemiBold, color = IdjThemeColors.text, modifier = Modifier.padding(vertical = 14.dp)) }
                    item { Text(if(searching)"输入书名或作者，搜索当前书源。" else if(categories.isEmpty())"此书源没有发现栏目，可搜索或导入其他书源。" else "选择左侧栏目，查看书源实时榜单",fontSize=13.sp, color = IdjThemeColors.secondary) }
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
                                    Text(if(searching)book.name else "${hits.indexOf(book)+1}. ${book.name}",color=IdjThemeColors.text,fontSize=17.sp,fontWeight=FontWeight.SemiBold,maxLines=2)
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
    if(showDownload) NovelPage("缓存与下载",{showDownload=false}) {
        NovelPanel {
            Text(currentBook?.name.orEmpty(),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Text("已缓存 $cachedCount / ${chapters.size} 章",fontSize=13.sp,color=IdjThemeColors.secondary)
            if(downloadBusy){
                LinearProgressIndicator(progress={if(downloadTotal==0)0f else downloadDone.toFloat()/downloadTotal},modifier=Modifier.fillMaxWidth())
                Text("本次 $downloadDone / $downloadTotal",fontSize=13.sp)
                OutlinedButton(onClick={downloadJob?.cancel()}){Text("暂停下载")}
            }
            error?.let {Text(it,fontSize=13.sp,color=MaterialTheme.colorScheme.error)}
        }
        NovelPanel {
            Text("离线下载",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={download(50)},enabled=!downloadBusy,modifier=Modifier.weight(1f)){Text("缓存后 50 章")}
                OutlinedButton(onClick={download(200)},enabled=!downloadBusy,modifier=Modifier.weight(1f)){Text("缓存后 200 章")}
            }
            Button(onClick={download(null)},enabled=!downloadBusy,modifier=Modifier.fillMaxWidth()){Text("下载全本 / 继续未完成下载")}
        }
        NovelPanel {
            Text("提前缓存",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf(0,3,10,20).forEach{n->
                FilterChip(selected=prefetch==n,onClick={prefetch=n;prefs.edit().putInt("prefetch",n).apply()},label={Text(if(n==0)"关闭" else "$n 章")})
            }}
            Text("阅读时自动预取后续章节，减少翻页等待。",fontSize=12.sp,color=IdjThemeColors.secondary)
        }
        Text("保持应用前台下载。已缓存章节可断网阅读，暂停或中断后可继续，已完成章节不会重复请求。",fontSize=12.sp,color=IdjThemeColors.secondary)
    }
    if(showSwitch)AlertDialog(onDismissRequest={switchJob?.cancel();showSwitch=false},title={Text("切换书籍来源")},text={LazyColumn{
        if(switchBusy)item{Text("正在搜索同名书籍…")}
        items(alternatives,key={it.first.id+it.second.url}){(s,book)->TextButton(onClick={work{
            val oldTitle=chapters.getOrNull(chapterIndex)?.title
            val toc=withContext(Dispatchers.IO){NovelSources.chapters(s,book.url)}
            require(toc.isNotEmpty()){"该源目录为空"}
            val match=toc.indexOfFirst{it.title.replace(Regex("\\s+"),"")==oldTitle?.replace(Regex("\\s+"),"")}.takeIf{it>=0} ?: chapterIndex.coerceIn(0,toc.lastIndex)
            withContext(Dispatchers.IO){cache.saveBook(s.id,book,toc)}
            switchJob?.cancel();selected=s.id;currentBook=book;chapters=toc;showSwitch=false;read(match,s,book)
        }}){Text(s.name)}}
        if(!switchBusy&&alternatives.isEmpty())item{Text("未找到同名同作者书籍，可启用更多书源后重试")}
    }},confirmButton={TextButton(onClick={switchJob?.cancel();showSwitch=false}){Text("关闭")}})
    if (importing) NovelPage("书源配置",{importing=false}) {
        NovelPanel {
            Text("导入书源",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Text("粘贴 Legado JSON 或书源订阅网址",fontSize=13.sp,color=IdjThemeColors.secondary)
            OutlinedTextField(importText, { importText = it }, Modifier.fillMaxWidth(),minLines=3,maxLines=7,label={Text("网址 / JSON")})
            error?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp) }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = { work {
            val raw = importText.trim()
            val data = if (raw.startsWith("https://") || raw.startsWith("http://")) withContext(Dispatchers.IO) { NovelSources.fetch(raw, NovelSource(JSONObject().put("bookSourceUrl", raw))) } else raw
            addSources(data)
            } }, enabled = !busy,modifier=Modifier.fillMaxWidth()) { Text("导入") }
            OutlinedButton(onClick={filePicker.launch(arrayOf("application/json","text/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("从文件导入")}
        }
        NovelPanel {
            Text("已安装 ${sources.size} 个 · 启用 ${sources.count{it.enabled}} 个",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            if(sources.isEmpty())Text("还没有书源，导入后即可搜索和浏览榜单。",fontSize=13.sp,color=IdjThemeColors.secondary)
            sources.toList().forEach { s ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name,fontSize=16.sp)
                        Text(runCatching{java.net.URI(s.id).host}.getOrNull().orEmpty(),fontSize=12.sp,color=IdjThemeColors.secondary,maxLines=1)
                    }
                    Switch(checked=s.enabled,onCheckedChange={on->
                        val i=sources.indexOfFirst{it.id==s.id};if(i>=0)sources[i]=NovelSource(JSONObject(s.json.toString()).put("enabled",on));saveSources()
                    })
                    TextButton(onClick={sources.removeAll{it.id==s.id};saveSources();if(selected==s.id)selected=sources.firstOrNull()?.id.orEmpty()}){Text("删除")}
                }
                HorizontalDivider(color=IdjThemeColors.elevated)
            }
        }
    }
}
