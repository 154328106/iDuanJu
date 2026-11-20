package app.iduanju.ui.profile

import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.*
import app.iduanju.ui.LocalNavigate
import app.iduanju.ui.components.*
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object ActorRanking {
    val actors = mutableStateListOf<HGRankedActor>()
    var date by mutableStateOf("")
    var busy by mutableStateOf(false)
    var more by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    private var offset = 0
    private var session = ""
    private var loadedAt = 0L
    val stale get() = actors.isEmpty() || SystemClock.elapsedRealtime() - loadedAt > 900_000

    suspend fun load(refresh: Boolean = false) {
        if (busy || (!refresh && !more)) return
        busy = true
        error = null
        try {
            val page = withContext(Dispatchers.IO) {
                if (refresh) HGActorRank.fetch(IdjApp.instance.hongguo)
                else HGActorRank.fetch(IdjApp.instance.hongguo, offset, session, actors.size)
            }
            if (refresh) actors.clear()
            val seen = actors.mapTo(hashSetOf()) { it.id }
            val fresh = page.actors.filter { seen.add(it.id) }
            actors.addAll(fresh.take(300 - actors.size))
            if (refresh || page.date.isNotEmpty()) date = page.date
            more = page.hasMore && fresh.isNotEmpty() && actors.size < 300
            offset = page.nextOffset
            session = page.sessionId
            loadedAt = SystemClock.elapsedRealtime()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "榜单加载失败" }
        finally { busy = false }
    }
}

internal fun actorRoute(actor: HGCelebrity) = "actor/${Uri.encode(actor.id)}?name=${Uri.encode(actor.name)}&avatar=${Uri.encode(actor.avatar)}&role=${Uri.encode(actor.role)}&intro=${Uri.encode(actor.intro)}"

@Composable
fun ActorRankScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(IdjThemeColors.bg).systemBarsPadding()) {
        DSNavBar("剧星榜", leading = { BackButton(onClick = onBack) })
        ActorRankList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActorRankList(bottomPadding: Dp = 24.dp, header: (@Composable () -> Unit)? = null) {
    val store = ActorRanking
    var gender by rememberSaveable { mutableStateOf(ActorGender.All) }
    val scope = rememberCoroutineScope()
    val navigate = LocalNavigate.current
    val open = { a: HGRankedActor ->
        val role = listOf("剧星榜 No.${a.rank}", a.score).filter(String::isNotEmpty).joinToString(" · ")
        navigate(actorRoute(HGCelebrity(a.id, a.name, a.avatar, role, a.intro)))
    }
    LaunchedEffect(Unit) { if (store.stale) store.load(refresh = true) }
    val list = store.actors.filter { gender == ActorGender.All || it.gender == gender }
    LaunchedEffect(gender, list.size, store.busy, store.more, store.error) {
        if (list.size < 20 && store.more && !store.busy && store.error == null) scope.launch { store.load() }
    }
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
        if (!store.busy) scope.launch {
            refreshing = true
            try { store.load(refresh = true) } finally { refreshing = false }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (header != null) item { header() }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Stars, null, tint = IdjThemeColors.text)
                    Text("剧星榜", Modifier.weight(1f).padding(start = 6.dp), color = IdjThemeColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (store.date.isNotBlank()) Text("统计截至 ${store.date}", color = IdjThemeColors.secondary, fontSize = 12.sp)
                }
            }
            item { SegmentedTabs(ActorGender.entries, gender, { it.label }, { gender = it }) }
            val podiumSize = if (list.size >= 3) 3 else 0
            if (podiumSize == 3) item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    for (i in listOf(1, 0, 2)) PodiumCell(list[i], i + 1, Modifier.weight(1f), open)
                }
            }
            itemsIndexed(list.drop(podiumSize), key = { _, a -> a.id }) { index, actor ->
                RankRow(actor, if (gender == ActorGender.All) actor.rank else index + podiumSize + 1, open)
            }
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        store.error != null -> TextButton(onClick = { scope.launch { store.load(refresh = store.actors.isEmpty() || !store.more) } }) { Text("${store.error} · 重试") }
                        store.busy -> CircularProgressIndicator(Modifier.size(24.dp))
                        store.more -> LaunchedEffect(store.actors.size) { scope.launch { store.load() } }
                        else -> Text(if (list.isEmpty()) "暂无榜单数据" else "已经到底了", color = IdjThemeColors.secondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActorAvatar(url: String, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(url, null, modifier.size(size).clip(CircleShape).background(IdjThemeColors.glass), contentScale = ContentScale.Crop)
}

@Composable
private fun ChangeBadge(a: HGRankedActor) {
    val (label, color) = when {
        a.change == 1 && a.changeNum > 0 -> "▲ ${a.changeNum}" to Color(0xFFFF5959)
        a.change == 2 && a.changeNum > 0 -> "▼ ${a.changeNum}" to Color(0xFF59CC80)
        else -> "—" to IdjThemeColors.secondary
    }
    Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

@Composable
private fun PodiumCell(a: HGRankedActor, place: Int, modifier: Modifier, open: (HGRankedActor) -> Unit) {
    val tint = when (place) { 1 -> Color(0xFFFFC740); 2 -> Color(0xFFC7D1E6); else -> Color(0xFFDB8C52) }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Brush.verticalGradient(listOf(tint.copy(alpha = .22f), IdjThemeColors.glass)))
        .border(1.dp, tint.copy(alpha = .35f), shape).clickable(onClickLabel = "第${place}名，${a.name}") { open(a) }
        .padding(vertical = 12.dp).padding(top = if (place == 1) 0.dp else 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(contentAlignment = Alignment.TopCenter) {
            ActorAvatar(a.avatar, if (place == 1) 84.dp else 66.dp, Modifier.padding(top = 14.dp).border(2.5.dp, tint, CircleShape))
            Text(when (place) { 1 -> "👑"; 2 -> "🥈"; else -> "🥉" }, fontSize = if (place == 1) 20.sp else 15.sp)
        }
        Text(a.name, color = IdjThemeColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.score, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        ChangeBadge(a)
    }
}

@Composable
private fun RankRow(a: HGRankedActor, rank: Int, open: (HGRankedActor) -> Unit) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.glass).clickable { open(a) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$rank", Modifier.width(30.dp), color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        ActorAvatar(a.avatar, 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.name, Modifier.weight(1f, fill = false), color = c.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (a.gender != ActorGender.All) {
                    val tint = if (a.gender == ActorGender.Female) Color(0xFFE75C94) else c.accent
                    Text(if (a.gender == ActorGender.Female) "女" else "男", Modifier.background(tint.copy(.2f), CircleShape).padding(horizontal = 5.dp), color = tint, fontSize = 11.sp)
                }
                ChangeBadge(a)
            }
            if (a.works.isNotEmpty()) Text(a.works.joinToString(" · "), color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.intro.isNotEmpty()) Text(a.intro, color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(a.score, color = c.accentWarm, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("›", color = c.secondary)
        }
    }
}
