package app.iduanju.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.*
import app.iduanju.ui.LocalNavigate
import app.iduanju.ui.components.BackButton
import app.iduanju.ui.components.DSNavBar
import app.iduanju.ui.home.HomePosterCard
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ActorProfileScreen(actor: HGCelebrity, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val client = IdjApp.instance.hongguo
    var profile by remember(actor.id) { mutableStateOf<HGActorProfile?>(null) }
    var works by remember(actor.id) { mutableStateOf<HGActorWorks?>(null) }
    var error by remember(actor.id) { mutableStateOf<String?>(null) }
    var attempt by remember(actor.id) { mutableIntStateOf(0) }
    LaunchedEffect(actor.id, attempt) {
        error = null
        launch {
            try { profile = withContext(Dispatchers.IO) { client.celebrityProfile(actor.id) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The ranking provides the initial profile. */ }
        }
        try { works = withContext(Dispatchers.IO) { client.celebrityWorks(actor.id) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "作品加载失败" }
    }
    val c = IdjThemeColors
    val go = LocalNavigate.current
    val name = profile?.actor?.name.orEmpty().ifBlank { actor.name }
    val intro = profile?.actor?.intro.orEmpty().ifBlank { actor.intro }
    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        DSNavBar(name, leading = { BackButton(onClick = onBack) })
        LazyVerticalGrid(GridCells.Fixed(app.iduanju.ui.components.LocalLayoutMetrics.current.posterCols), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActorAvatar(profile?.actor?.avatar.orEmpty().ifBlank { actor.avatar }, 104.dp)
                    Text(name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.text)
                    if (actor.role.isNotBlank()) Text(actor.role, color = c.secondary, fontSize = 14.sp)
                }
            }
            if (intro.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) { Text(intro, color = c.secondary, fontSize = 14.sp) }
            val related = profile?.related.orEmpty()
            if (related.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("合作演员", color = c.text, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(related, key = { it.id }) { other ->
                            Column(Modifier.width(68.dp).clickable { go(actorRoute(other)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                ActorAvatar(other.avatar, 56.dp)
                                Text(other.name, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(works?.total?.takeIf { it > 0 }?.let { "参演作品 · $it" } ?: "参演作品", color = c.text, fontWeight = FontWeight.Bold)
            }
            items(works?.items.orEmpty(), key = { it.seriesId }) { HomePosterCard(it, onOpen) }
            if (works?.items.isNullOrEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    error != null -> TextButton(onClick = { attempt++ }) { Text("$error · 重试") }
                    works == null -> Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    else -> Text("暂无作品", color = c.secondary)
                }
            }
        }
    }
}
