package app.iduanju.ui

/** Debug-only native-simulator evidence hooks, populated only in a Debug build's MainActivity. */
object UiEvidence {
    var keepChrome = false
    var page: String = ""
    var panel: String? = null
    var scroll = false
    var landscape = false
    var switchEpisode = false
    /** Written by PlayerScreen's position poll in every build; native playback tests read them. */
    @Volatile var playerPositionMs = 0L
    @Volatile var playerPlaying = false
}
