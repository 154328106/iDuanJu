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
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import app.iduanju.ui.components.*
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelScreen(onBack: () -> Unit, embedded: Boolean = false, onFullScreenChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("novel", 0) }
    val sources = remember { mutableStateListOf<NovelSource>().apply { addAll(runCatching { NovelSources.import(prefs.getString("sources", "[]")!!) }.getOrDefault(emptyList())) } }
    var selected by remember { mutableStateOf(prefs.getString("rankSource", null)?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id ?: "") }
    var query by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="import") }
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
    var searching by remember { mutableStateOf(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="search") }
    var shelf by remember { mutableStateOf(!(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="rank")) }
    var sourcePage by remember { mutableStateOf(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="sources") }
    var importResult by remember { mutableStateOf<String?>(null) }
    val cache = remember { NovelCache(File(context.filesDir,"novel-cache-v2")) }
    var font by remember { mutableFloatStateOf(prefs.getFloat("font", 20f)) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val readerState = rememberLazyListState()
    val source = sources.firstOrNull { it.id == selected }
    val fullScreen = searching || !shelf || sourcePage || currentBook != null || text.isNotEmpty()
    LaunchedEffect(fullScreen) { if (embedded) onFullScreenChange(fullScreen) }
    DisposableEffect(Unit) { onDispose { if (embedded) onFullScreenChange(false) } }
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
        saveSources(); selected = parsed.first().id; importResult = "已导入 ${parsed.size} 个书源"
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
    fun search(reset: Boolean, cat: NovelCategory? = null) {
        if(searching){keyboard?.hide();focus.clearFocus()}
        work {
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
        prefs.edit().putString("rankSource", selected).apply()
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
    fun back() { readGeneration++; prefetchJob?.cancel(); if (text.isNotEmpty()) text = "" else if (currentBook != null) { currentBook = null; chapters = emptyList() } else if(sourcePage) sourcePage=false else if(searching) { searching=false;hits=emptyList();category=null;more=false } else if(!shelf) shelf=true else onBack() }
    androidx.activity.compose.BackHandler(enabled=!embedded || !shelf || sourcePage || searching || currentBook!=null || text.isNotEmpty()) { back() }
    val c=IdjThemeColors
    if(!sourcePage) Column(Modifier.fillMaxSize().background(c.bg).then(if(embedded)Modifier else Modifier.statusBarsPadding())) {
        if(!shelf || searching || currentBook!=null || text.isNotEmpty()) DSNavBar(
            if (text.isNotEmpty()) chapters.getOrNull(chapterIndex)?.title ?: "本地小说" else if(currentBook!=null) "书籍详情" else if(searching) "搜书" else "小说榜单",
            leading={BackButton{back()}},trailing={if(searching)CapsuleButton("书源",Icons.Rounded.Cloud){sourcePage=true} else RoundIconButton(Icons.Rounded.Search,"搜书"){searching=true}})
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
        } else if(shelf && !searching) {
            NovelShelf(cache.books().map{it.source to it.book},File(context.filesDir,"novel-local.txt").exists(),
                onBook={id,book->val saved=cache.books().firstOrNull{it.source==id&&it.book.url==book.url};if(saved!=null){selected=id;currentBook=book;chapters=saved.chapters;cachedCount=cache.count(id,book.url,saved.chapters)}},
                onLocal={work{text=withContext(Dispatchers.IO){File(context.filesDir,"novel-local.txt").readText()};localBook=true}},
                onImport={importResult=null;importing=true},onSearch={searching=true;hits=emptyList();more=false;category=null},
                onRank={shelf=false;searching=false;hits=emptyList()},onSources={sourcePage=true},embedded=embedded)
        } else {
            var expanded by remember { mutableStateOf(false) }
            if(searching) {
                NovelSearchField(query,{query=it},{if(!busy&&query.isNotBlank())search(true)})
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(busy)"正在搜索当前书源…" else "共 ${hits.size} 本 · ${source?.name ?: "请先选择书源"}",color=c.secondary,fontSize=12.sp,modifier=Modifier.weight(1f))
                    if(query.isNotBlank())MiniButton("搜索"){if(!busy)search(true)}
                }
                if(busy)DSProgressBar(0.5f,Modifier.padding(horizontal=20.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Box {
                    CapsuleButton(source?.name ?: "选择书源",Icons.Rounded.Cloud,Icons.Rounded.ExpandMore){expanded=true}
                    DropdownMenu(expanded,{expanded=false}){sources.filter{it.enabled}.forEach{sc->DropdownMenuItem(text={Text(sc.name)},onClick={selected=sc.id;expanded=false})}}
                }
                Spacer(Modifier.weight(1f))
                if(!searching)Text("实时书源榜单",color=c.tertiary,fontSize=11.sp)
            }
            if(!searching && categories.isNotEmpty()) UnderlineTabs(categories.map{it.group}.distinct(),selectedGroup,{it},small=true,onSelect={group->
                selectedGroup=group
                val first=categories.firstOrNull{it.group==group&&it.action==null&&it.url.isNotBlank()}
                if(first!=null)search(true,first) else {hits=emptyList();category=null;more=false}
            })
            Row(Modifier.weight(1f)) {
                if(!searching&&categories.isNotEmpty()) LazyColumn(Modifier.width(86.dp).fillMaxHeight().background(c.rail),contentPadding=PaddingValues(vertical=8.dp)) {
                    items(categories.filter{it.group==selectedGroup},key={it.id}){cat->
                        val on=cat==category
                        Text(cat.title,color=if(on)c.accent else c.secondary,fontSize=13.sp,fontWeight=if(on)FontWeight.SemiBold else FontWeight.Normal,
                            modifier=Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=3.dp).clip(RoundedCornerShape(12.dp)).background(if(on)c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                            .pressClick(enabled=!busy){if(cat.action!=null)work{withContext(Dispatchers.IO){NovelSources.categoryAction(source!!,cat)};categories=withContext(Dispatchers.IO){NovelSources.categories(source!!)}} else search(true,cat)}
                            .padding(horizontal=10.dp,vertical=14.dp))
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(),contentPadding=PaddingValues(bottom=32.dp)) {
                    if(hits.isEmpty())item{EmptyNovel(if(searching)"搜索你的下一本书" else "书源推荐与榜单",if(source==null)"添加书源后，即可搜索和浏览实时榜单。" else if(searching)"输入书名或作者，搜索当前书源。" else "选择左侧栏目，查看书源实时榜单。")}
                    items(hits,key={it.url}){book->
                        NovelResult(book,if(searching)null else hits.indexOf(book)+1,source?.name.orEmpty()) {work{
                            val sc=source?:error("请先选择书源")
                            val result=withContext(Dispatchers.IO){NovelSources.chapters(sc,book.url)}
                            withContext(Dispatchers.IO){cache.saveBook(sc.id,book,result)}
                            currentBook=book;chapters=result;cachedCount=cache.count(sc.id,book.url,result)
                        }};Hairline(Modifier.padding(start=16.dp))
                    }
                    if(more)item{TextButton(onClick={search(false)},enabled=!busy){Text("加载更多")}}
                }
            }
        }
    }
    if(sourcePage)NovelSourcesPage(sources.toList(),{sourcePage=false},{importResult=null;importing=true},onToggle={sc,on->
        val i=sources.indexOfFirst{it.id==sc.id};if(i>=0)sources[i]=NovelSource(JSONObject(sc.json.toString()).put("enabled",on));saveSources()
    },onDelete={sc->sources.removeAll{it.id==sc.id};saveSources();if(selected==sc.id)selected=sources.firstOrNull()?.id.orEmpty()})

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
    if(importing)ModalBottomSheet(onDismissRequest={importing=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=c.surface,shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp),dragHandle=null) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("导入书籍与书源",color=c.text,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));RoundIconButton(Icons.Rounded.Close,"关闭"){importing=false}}
            Text("书籍",color=c.secondary,fontSize=12.sp)
            Row(Modifier.fillMaxWidth().dsCard(16.dp).pressClick {filePicker.launch(arrayOf("text/*","application/octet-stream"));importing=false}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){IconWell(Icons.Rounded.MenuBook);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("导入本地书籍",color=c.text,fontSize=15.sp,fontWeight=FontWeight.SemiBold);Text("TXT · UTF-8 / GB18030 / UTF-16",color=c.secondary,fontSize=12.sp)};Icon(Icons.Rounded.ChevronRight,null,tint=c.tertiary)}
            Text("书源",color=c.secondary,fontSize=12.sp)
            OutlinedTextField(importText,{importText=it},Modifier.fillMaxWidth(),placeholder={Text("粘贴书源网址或 JSON")},shape=RoundedCornerShape(14.dp),maxLines=3,
                trailingIcon={TextButton(onClick={val clip=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager;importText=clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()}){Text("粘贴")}})
            PillButton(if(busy)"正在导入…" else "导入书源",Modifier.fillMaxWidth(),Icons.Rounded.Add,enabled=!busy&&importText.isNotBlank()) {work{
                val raw=importText.trim()
                val data=if(raw.startsWith("https://")||raw.startsWith("http://"))withContext(Dispatchers.IO){NovelSources.fetch(raw,NovelSource(JSONObject().put("bookSourceUrl",raw)))}else raw
                addSources(data)
            }}
            Row(Modifier.fillMaxWidth().pressClick {val clip=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager;importText=clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){IconWell(Icons.Rounded.ContentPaste);Spacer(Modifier.width(12.dp));Text("从剪贴板导入",color=c.text,fontSize=15.sp)}
            Hairline()
            Row(Modifier.fillMaxWidth().pressClick {filePicker.launch(arrayOf("application/json","text/*"))}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){IconWell(Icons.Rounded.FolderOpen);Spacer(Modifier.width(12.dp));Text("从文件导入书源",color=c.text,fontSize=15.sp)}
            importResult?.let{DSStatus(it,StatusTone.Ok)}
            error?.let{Text(it,color=c.accentWarm,fontSize=13.sp)}
            Text("支持 Legado 文本书源。书源与阅读记录仅保存在当前设备。",color=c.tertiary,fontSize=12.sp,lineHeight=19.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
}
