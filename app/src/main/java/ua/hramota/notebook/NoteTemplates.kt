package ua.hramota.notebook

import android.content.Context
import ua.hramota.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Ready-made notes: a to-do list, minutes of a meeting, lecture notes, a diary page, a week's plan. */
object NoteTemplates {
    val KEYS = listOf("todo", "minutes", "lecture", "diary", "week")

    fun label(key: String) = when (key) {
        "todo" -> R.string.tpl_todo; "minutes" -> R.string.tpl_minutes; "lecture" -> R.string.tpl_lecture
        "diary" -> R.string.tpl_diary; else -> R.string.tpl_week
    }

    /** The title and the blocks of a new note made from [key]. */
    fun make(context: Context, key: String): Pair<String, List<NBlock>> {
        var id = 1L
        fun t(text: String, style: String = "body") = NBlock.Text(id++, text, style)
        fun s(res: Int) = context.getString(res)
        val today = SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(java.util.Date())
        return when (key) {
            "todo" -> s(R.string.tpl_todo) to listOf(t("", "check"), t("", "check"), t("", "check"))
            "minutes" -> s(R.string.tpl_minutes) to listOf(
                t(s(R.string.tpl_date) + ": " + today), t(s(R.string.tpl_present) + ": "),
                t(s(R.string.tpl_agenda), "h2"), t("", "number"),
                t(s(R.string.tpl_decided), "h2"), t("", "number"),
                t(s(R.string.tpl_actions), "h2"), t("", "check"),
            )
            "lecture" -> s(R.string.tpl_lecture) to listOf(
                t(s(R.string.tpl_topic) + ": "), t(today),
                t(s(R.string.tpl_main), "h2"), t("", "bullet"),
                t(s(R.string.tpl_questions), "h2"), t("", "bullet"),
                t(s(R.string.tpl_summary), "h2"), t(""),
            )
            "diary" -> today to listOf(
                t(s(R.string.tpl_grateful), "h3"), t("", "bullet"),
                t(s(R.string.tpl_today), "h3"), t(""),
                NBlock.Ink(id++, emptyList(), 260f),
            )
            else -> {
                val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }
                val day = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
                val blocks = ArrayList<NBlock>()
                repeat(7) {
                    blocks += t(day.format(cal.time).replaceFirstChar { it.titlecase() }, "h3")
                    blocks += t("", "check")
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                s(R.string.tpl_week) to blocks
            }
        }
    }
}
