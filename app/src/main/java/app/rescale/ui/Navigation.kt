package app.rescale.ui

/** Where the user is. Everything except the two side pages follows from what the app is doing. */
internal enum class Screen { Home, Editor, Running, Result, Mask, Settings, Batch }

/** The two pages you open on purpose. */
internal enum class Page { Settings, Batch }

/**
 * Picks the screen. Order matters: an open side page wins, then a running job, then the
 * mask editor, then an unseen result, then the editor, then home.
 */
internal fun screenFor(
    page: Page?,
    running: Boolean,
    done: Boolean,
    resultDismissed: Boolean,
    editingMask: Boolean,
    hasInput: Boolean,
    hasThumbnail: Boolean,
): Screen = when {
    page == Page.Settings -> Screen.Settings
    page == Page.Batch -> Screen.Batch
    running -> Screen.Running
    editingMask && hasThumbnail -> Screen.Mask
    done && !resultDismissed -> Screen.Result
    hasInput -> Screen.Editor
    else -> Screen.Home
}

/** Where Back goes from each screen, or null when Back should leave the app. */
internal enum class BackTo { ClosePage, CancelMask, Editor, Home }

internal fun backFrom(screen: Screen): BackTo? = when (screen) {
    Screen.Settings, Screen.Batch -> BackTo.ClosePage
    Screen.Mask -> BackTo.CancelMask
    Screen.Result -> BackTo.Editor
    Screen.Editor -> BackTo.Home
    Screen.Home, Screen.Running -> null
}

/** Settings is reachable from the two screens where you're deciding something, nowhere else. */
internal fun showsSettings(screen: Screen) = screen == Screen.Home || screen == Screen.Editor

/** Titles for the top bar. The editor shows the file name instead when it has one. */
internal fun titleFor(screen: Screen, fileName: String?): String = when (screen) {
    Screen.Home -> ""
    Screen.Editor -> fileName ?: "Your image"
    Screen.Running -> "Working"
    Screen.Result -> "Result"
    Screen.Mask -> "Mark damage"
    Screen.Settings -> "Settings"
    Screen.Batch -> "Batch"
}
