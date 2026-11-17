package app.rescale.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    private fun screen(
        page: Page? = null,
        running: Boolean = false,
        done: Boolean = false,
        resultDismissed: Boolean = false,
        editingMask: Boolean = false,
        hasInput: Boolean = false,
        hasThumbnail: Boolean = hasInput,
    ) = screenFor(page, running, done, resultDismissed, editingMask, hasInput, hasThumbnail)

    @Test fun startsAtHome() = assertEquals(Screen.Home, screen())

    @Test fun pickedImageOpensEditor() = assertEquals(Screen.Editor, screen(hasInput = true))

    @Test fun runningJobWinsOverEditorAndMask() =
        assertEquals(Screen.Running, screen(running = true, hasInput = true, editingMask = true))

    @Test fun sidePagesWinOverEverything() {
        assertEquals(Screen.Settings, screen(page = Page.Settings, running = true, done = true, hasInput = true))
        assertEquals(Screen.Batch, screen(page = Page.Batch, running = true, done = true, hasInput = true))
    }

    @Test fun finishedJobShowsResultUntilDismissed() {
        assertEquals(Screen.Result, screen(done = true, hasInput = true))
        assertEquals(Screen.Editor, screen(done = true, resultDismissed = true, hasInput = true))
    }

    @Test fun maskNeedsAThumbnail() {
        assertEquals(Screen.Mask, screen(editingMask = true, hasInput = true))
        assertEquals(Screen.Editor, screen(editingMask = true, hasInput = true, hasThumbnail = false))
    }

    @Test fun maskWinsOverResult() =
        assertEquals(Screen.Mask, screen(editingMask = true, done = true, hasInput = true))

    @Test fun backNeverLeavesTheAppFromInsideAFlow() {
        assertEquals(BackTo.ClosePage, backFrom(Screen.Settings))
        assertEquals(BackTo.ClosePage, backFrom(Screen.Batch))
        assertEquals(BackTo.CancelMask, backFrom(Screen.Mask))
        assertEquals(BackTo.Editor, backFrom(Screen.Result))
        assertEquals(BackTo.Home, backFrom(Screen.Editor))
    }

    @Test fun backLeavesFromHomeAndWhileRunning() {
        assertNull(backFrom(Screen.Home))
        assertNull(backFrom(Screen.Running))
    }

    @Test fun settingsOnlyWhereYouDecideThings() {
        assertTrue(showsSettings(Screen.Home))
        assertTrue(showsSettings(Screen.Editor))
        (Screen.entries - Screen.Home - Screen.Editor).forEach { assertFalse("$it shows Settings", showsSettings(it)) }
    }

    @Test fun everyScreenHasATitleExceptHome() {
        Screen.entries.forEach { s ->
            val t = titleFor(s, null)
            if (s == Screen.Home) assertEquals("", t) else assertTrue("$s has no title", t.isNotBlank())
        }
        assertEquals("cat.jpg", titleFor(Screen.Editor, "cat.jpg"))
    }
}
