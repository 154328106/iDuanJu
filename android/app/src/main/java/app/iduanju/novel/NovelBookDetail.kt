package app.iduanju.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.core.novel.*
import app.iduanju.ui.components.*
import app.iduanju.ui.theme.IdjThemeColors

@Composable
internal fun NovelBookDetail(
    book: NovelHit, info: NovelBookDetails?, sourceName: String, chapters: List<NovelChapter>,
    loading: Boolean, error: String?, onShelf: Boolean, downloading: Boolean,
    downloadProgress: Float, onBack: () -> Unit, onSwitch: () -> Unit,
    onAdd: () -> Unit, onDownload: () -> Unit, onRead: (Int?) -> Unit, onRetry: () -> Unit, mediaType: Int = 0
) {
    val c = IdjThemeColors
    val title = info?.name.orEmpty().ifBlank { book.name }
    val author = info?.author.orEmpty().ifBlank { book.author }
    val kind = info?.kind.orEmpty().ifBlank { book.kind }
    val latest = info?.latest.orEmpty().ifBlank { book.latest }
    val scroll = rememberScrollState()
    val pinned by remember { derivedStateOf { scroll.value > 0 } }
    val chapterCount = chapters.size
    val enabled = chapterCount > 0 && (onShelf || !loading)
    val media = mediaType == 1 || mediaType == 4
    var sourceMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().dsAmbient(cool = true).testTag("novel.detail")) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    NovelPoster(book.copy(name = title, cover = info?.cover.orEmpty().ifBlank { book.cover }), Modifier.size(100.dp, 142.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, color = c.text, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                        if (author.isNotBlank()) Text(author, color = c.secondary, fontSize = 15.sp)
                        if (kind.isNotBlank()) Text(kind, color = c.secondary, fontSize = 12.sp, maxLines = 2)
                        if (chapterCount > 0) Text("共 $chapterCount 章", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp)) {
                    if (latest.isNotBlank()) {
                        Text("最新：$latest", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 14.dp))
                        Hairline()
                    }
                    Box {
                        TextButton(onClick = { sourceMenu = true }, modifier = Modifier.fillMaxWidth().testTag("novel.detail.source"), contentPadding = PaddingValues(vertical = 12.dp)) {
                            Text("书源：$sourceName", color = c.text, modifier = Modifier.weight(1f))
                            Text("换源", color = c.accent); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = c.accent)
                        }
                        DropdownMenu(sourceMenu, { sourceMenu = false }) {
                            DropdownMenuItem(text = { Text(sourceName) }, onClick = { sourceMenu = false }, trailingIcon = { Icon(Icons.Outlined.Check, null) })
                            DropdownMenuItem(text = { Text("搜索其他书源") }, onClick = { sourceMenu = false; onSwitch() })
                        }
                    }
                }
                info?.intro?.trim()?.takeIf { it.isNotEmpty() }?.let { intro ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("内容简介", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(intro, color = c.secondary, fontSize = 16.sp, lineHeight = 25.sp, modifier = Modifier.testTag("novel.detail.intro"))
                    }
                }
                if (!media && chapters.isNotEmpty()) Column {
                    Text("试读章节", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    chapters.take(3).forEachIndexed { index, chapter ->
                        TextButton(onClick = { onRead(index) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) {
                            Text(chapter.title, color = c.text, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = c.secondary)
                        }
                        Hairline()
                    }
                }
                if (loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在读取目录…", color = c.secondary)
                }
                if (error != null) Column { Text(error, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("重试") } }
                if (downloading) LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxWidth().dsPinned(pinned).statusBarsPadding()) {
                DSNavBar(if (pinned) title else "书籍详情", leading = { BackButton(onClick = onBack) })
            }
        }
        Row(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAdd, enabled = enabled && !onShelf && !media, modifier = Modifier.testTag("novel.detail.add"), contentPadding = PaddingValues(4.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (onShelf) Icons.Outlined.Check else Icons.Outlined.LibraryBooks, null, Modifier.size(19.dp))
                    Text(if (onShelf) "已在书架" else "加入书架", fontSize = 12.sp)
                }
            }
            TextButton(onClick = onDownload, enabled = enabled && !media, modifier = Modifier.testTag("novel.detail.download"), contentPadding = PaddingValues(4.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.Download, null, Modifier.size(19.dp)); Text("下载", fontSize = 12.sp) }
            }
            Button(onClick = { onRead(null) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("novel.detail.read"), colors = ButtonDefaults.buttonColors(containerColor = c.accent)) {
                Text(when (mediaType) { 1 -> "开始收听"; 4 -> "开始观看"; else -> "开始阅读" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
