package app.iduanju.novel

import androidx.compose.runtime.*
import app.iduanju.core.novel.NovelHit
import app.iduanju.core.novel.NovelSource
import app.iduanju.core.novel.NovelSources
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class SearchEdition(val source: NovelSource, val book: NovelHit)
internal data class BookMatches(val editions: List<SearchEdition>) {
    val first get() = editions.first()
}

internal class NovelSearchResults {
    var groups by mutableStateOf(emptyList<BookMatches>())
        private set
    var total by mutableIntStateOf(0)
        private set
    var finished by mutableIntStateOf(0)
        private set
    var failures by mutableIntStateOf(0)
        private set
    private var job: Job? = null

    fun clear() {
        job?.cancel()
        groups = emptyList()
        total = 0
        finished = 0
        failures = 0
    }

    fun submit(scope: CoroutineScope, sources: List<NovelSource>, query: String) {
        val term = query.trim()
        if (term.isEmpty()) return
        clear()
        val enabled = sources.filter { it.enabled }
        total = enabled.size
        job = scope.launch {
            val gate = Semaphore(8)
            enabled.forEach { source -> launch {
                gate.withPermit {
                    val hits = try { withContext(Dispatchers.IO) { NovelSources.search(source, term) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failures++; emptyList() }
                    ensureActive()
                    val merged = groups.toMutableList()
                    for (hit in hits) {
                        val index = merged.indexOfFirst { group ->
                            val book = group.first.book
                            book.name == hit.name && (book.author.isBlank() || hit.author.isBlank() || book.author.trim() == hit.author.trim())
                        }
                        val edition = SearchEdition(source, hit)
                        if (index < 0) merged.add(BookMatches(listOf(edition)))
                        else if (merged[index].editions.none { it.source.id == source.id }) {
                            merged[index] = BookMatches(merged[index].editions + edition)
                        }
                    }
                    groups = merged.sortedWith(compareBy<BookMatches> {
                        val b = it.first.book
                        when { b.name == term -> 0; term in b.name || b.author == term -> 1; else -> 2 }
                    }.thenByDescending { it.editions.size })
                    finished++
                }
            } }
        }
    }
}
