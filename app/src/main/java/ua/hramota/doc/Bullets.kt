package ua.hramota.doc

/** List bullets as written in files, shown as ordinary characters. */
object Bullets {
    /** Symbol-font bullets (Word's, PowerPoint's, LibreOffice's) are private-use characters no font here draws. */
    fun shown(c: String?): String = when (val ch = c?.firstOrNull()) {
        null -> "•"
        '', '·' -> "•"
        '', '§' -> "▪"
        '', 'Ø' -> "➢"
        '', 'ü' -> "✓"
        'q' -> "❑"
        'v' -> "❖"
        else -> if (ch.code in 0xE000..0xF8FF) "•" else c
    }
}
