package app.iduanju

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.iduanju.ui.RootScreen
import app.iduanju.ui.home.HomeSubSel
import app.iduanju.ui.theme.IdjTheme
import org.junit.Rule
import org.junit.Test

class ActorRankNavigationTest {
    @get:Rule val ui = createComposeRule()

    @Test fun homeFiltersOpenRankedActorAndWorks() {
        val previous = ui.runOnIdle { HomeSubSel.map.remove("红果") }
        try {
            ui.setContent { IdjTheme { RootScreen() } }
            ui.onNodeWithContentDescription("seg.剧星榜").performClick()
            val winner = SemanticsMatcher("first-place actor") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label?.startsWith("第1名，") == true
            }
            for (gender in listOf("全部", "男演员", "女演员")) {
                ui.onNodeWithContentDescription("seg.$gender").performClick()
                ui.waitUntil(60_000) { ui.onAllNodes(winner).fetchSemanticsNodes().isNotEmpty() }
                ui.onNode(winner).assertIsDisplayed()
            }
            val name = ui.onNode(winner).fetchSemanticsNode().config[SemanticsActions.OnClick].label!!.substringAfter('，')
            ui.onNode(winner).performClick()
            ui.onAllNodesWithText(name).onFirst().assertIsDisplayed()
            val works = hasText("参演作品 · ", substring = true)
            ui.waitUntil(60_000) { ui.onAllNodes(works).fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(works).assertIsDisplayed()
        } finally {
            ui.runOnIdle {
                if (previous == null) HomeSubSel.map.remove("红果") else HomeSubSel.map["红果"] = previous
            }
        }
    }
}
