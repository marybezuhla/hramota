package ua.hramota.screens

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.hramota.doc.docx.edit.DocxEdit
import ua.hramota.doc.docx.edit.TableOp
import ua.hramota.doc.odf.OdpEdit
import ua.hramota.doc.odf.OdtEdit
import ua.hramota.doc.sheet.edit.XlsxEdit
import ua.hramota.doc.slides.edit.PptxEdit
import ua.hramota.ui.DocxEditorScreen
import ua.hramota.ui.EditorState
import ua.hramota.ui.HramotaTheme
import ua.hramota.ui.SheetEditorScreen
import ua.hramota.ui.SheetEditorState
import ua.hramota.ui.SlideEditorScreen
import ua.hramota.ui.SlideEditorState
import java.io.File

/**
 * People of different kinds doing what they came to do in the editors, step by step, as they would
 * by touch: an older woman with a large font on a phone, an accountant on a tablet, a teacher with a
 * LibreOffice table, a student with slides on a phone, a designer on a tablet in the dark. Each step
 * is drawn (build/screens/persona-*.png). Along the way every button on the screen is checked as
 * a screen reader would meet it — a name to read aloud, room for a finger — and what is found is
 * written to build/screens/personas.txt for review (not failing the build: some are judgement).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w400dp-h860dp-xhdpi")
class PersonasTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    companion object {
        private val report = StringBuilder()
        @JvmStatic @AfterClass fun write() {
            File("build/screens").apply { mkdirs() }.let { File(it, "personas.txt").appendText(report.toString()) }
        }
    }

    private fun note(line: String) { synchronized(report) { report.append(line).append('\n') } }

    /** A person's session: the screen shown, then steps, each drawn and its buttons looked over. */
    private inner class Session(val who: String, val scenario: ActivityScenario<ComponentActivity>) {
        var step = 0
        fun settle(times: Int = 6) = repeat(times) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            compose.mainClock.advanceTimeBy(200)
            Thread.sleep(30)
            compose.waitForIdle()
        }
        fun shot(what: String) {
            settle()
            var bitmap: Bitmap? = null
            scenario.onActivity { a -> val v = a.window.decorView; bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) } }
            val dir = File("build/screens").apply { mkdirs() }
            File(dir, "persona-$who-${++step}-$what.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            audit(what)
        }
        /** Every button: a name a screen reader can say, and at least 48 dp each way for a finger. */
        fun audit(what: String) {
            val density = ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density
            val seen = HashSet<String>()
            for (n in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
                val name = label(n)
                val w = n.boundsInRoot.width / density; val h = n.boundsInRoot.height / density
                if (w <= 0f || h <= 0f) continue // scrolled away
                val key = "$name@${n.boundsInRoot.left.toInt()}"
                if (!seen.add(key)) continue
                if (name.isBlank()) note("$who · $what · NO NAME: a button at ${n.boundsInRoot} (${w.toInt()}×${h.toInt()} dp)")
                // (Material's buttons drawn 40 dp take touches 48 dp round; smaller is too small for a finger)
                else if (n.config.contains(SemanticsActions.OnClick) && n.config.getOrNull(SemanticsProperties.EditableText) == null && (w < 39.5f || h < 39.5f))
                    note("$who · $what · SMALL: «$name» ${w.toInt()}×${h.toInt()} dp")
            }
        }
        private fun label(n: SemanticsNode): String {
            val c = n.config
            val parts = (c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(c.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotBlank() }))
            return parts.joinToString(" ").ifBlank { c.getOrNull(SemanticsActions.OnClick)?.label ?: "" }
        }
    }

    private fun session(who: String, content: @Composable () -> Unit, body: Session.() -> Unit) =
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { a -> a.setContent { HramotaTheme(day = "nebo", night = "zoriana_nich") { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } } }
            val s = Session(who, scenario)
            s.settle(8)
            s.body()
        }

    private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    /** Halyna, 72, on her phone with the largest font: a typo in a letter put right, a word made bold, saved. */
    @Config(fontScale = 1.6f)
    @Test fun halynaFixesALetter() {
        val state = EditorState(DocxEdit.open(res("docs/03-lists.docx").inputStream()))
        var saved = false
        session("halyna", { DocxEditorScreen("Лист.docx", state, saving = false, onSave = { saved = true }, onClose = {}) }) {
            shot("opened")
            // She chooses all of a paragraph and types it anew (as the keyboard's "select all" does)
            val p = state.paras.first { it.editable && it.text.startsWith("Перший") }
            state.focused = p.key
            compose.onAllNodes(hasSetTextAction() and hasText(p.text, substring = true)).onFirst().performTextReplacement(p.text + " терміново")
            shot("typed")
            assertTrue("Typed over, the paragraph was joined to the one before", state.paras.any { it.text == p.text + " терміново" })
            assertTrue(state.paras.any { it.text == "Списки" })
            // Bold, from the row under her thumb
            compose.onAllNodes(hasContentDescription("Жирний", substring = true)).onFirst().performClick()
            shot("bold")
            compose.onAllNodesWithText("Зберегти").onFirst().performClick()
            settle()
            assertTrue("Saving was not asked", saved)
        }
    }

    /** Oksana, an accountant, on a tablet: numbers typed down a column, each ✓ to the cell below, a sum made. */
    @Config(qualifiers = "uk-w1200dp-h800dp-mdpi")
    @Test fun oksanaTypesAColumn() {
        val edit = XlsxEdit.open(res("sheets/01-budget.xlsx").inputStream())
        val state = SheetEditorState(edit, edit.readBack(res("sheets/01-budget.xlsx").inputStream()))
        session("oksana", { SheetEditorScreen("Бюджет.xlsx", state, saving = false, onSave = {}, onClose = {}, startCell = 12 to 1) }) {
            shot("opened")
            for (v in listOf("100", "250,5", "300")) {
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(v)
                compose.onAllNodes(hasContentDescription("Внести")).onFirst().performClick()
                settle()
            }
            shot("typed")
            // Each number went into its own cell, one under another
            assertEquals("100", edit.content(ua.hramota.doc.sheet.edit.CellKey(0, 12, 1)))
            assertEquals("300", edit.content(ua.hramota.doc.sheet.edit.CellKey(0, 14, 1)))
            // The ribbon's AutoSum under them
            compose.onAllNodesWithText("Автосума").onFirst().performClick()
            shot("sum")
            // Only the numbers she typed: not the date nor the share above them
            assertEquals("=SUM(B13:B15)", edit.content(ua.hramota.doc.sheet.edit.CellKey(0, 15, 1)))
        }
    }

    /** Mariia, a teacher, with a LibreOffice text on a tablet: two cells of her table made one, a row added under. */
    @Config(qualifiers = "uk-w1200dp-h800dp-mdpi")
    @Test fun mariiaMergesCells() {
        val edit = OdtEdit.open(res("odf/04-tables.odt").inputStream())
        val state = EditorState(edit)
        session("mariia", { DocxEditorScreen("Таблиці.odt", state, saving = false, onSave = {}, onClose = {}) }) {
            state.focused = state.paras.first { it.text == "Папір офісний А4" }.key
            shot("opened")
            compose.onAllNodesWithText("Вставка").onFirst().performClick()
            shot("insert-tab")
            compose.onAllNodesWithText("Об'єднати з нижньою").onFirst().performClick()
            shot("merged")
            val merged = edit.shown(state.paras)
            assertTrue("The two cells' texts are not in one cell", state.paras.indexOfFirst { it.text == "Папір офісний А4" }.let { i -> i >= 0 && state.paras.getOrNull(i + 1)?.text?.startsWith("Ручки") == true && !merged[i + 1].cellStart })
            compose.onAllNodesWithText("Рядок нижче").onFirst().performClick()
            shot("row-below")
            compose.onAllNodesWithText("Розділити клітинку").onFirst().performClick()
            shot("split")
        }
    }

    /** Taras, a student, on his phone just before his talk: speaker notes for his first slide, typed. */
    @Test fun tarasAddsNotes() {
        val state = SlideEditorState(OdpEdit.open(res("odf/01-project.odp").inputStream()))
        session("taras", { SlideEditorScreen("Доповідь.odp", state, saving = false, onSave = {}, onClose = {}) }) {
            state.focused = state.paras.first { it.editable }.key
            shot("opened")
            compose.onAllNodes(hasContentDescription("Нотатки")).onFirst().performClick()
            shot("notes")
            val notes = state.paras.firstOrNull { state.edit.boxes[it.box].let { b -> b.kind == "notes" && b.slide == 0 } }
            assertNotNull("No notes for the first slide", notes)
        }
    }

    /** Lina, a designer, on a tablet at night: a new slide from the ribbon, the status line following. */
    @Config(qualifiers = "uk-w1200dp-h800dp-night-mdpi")
    @Test fun linaAddsASlide() {
        val state = SlideEditorState(PptxEdit.open(res("slides/01-project.pptx").inputStream()))
        val before = state.order.size
        session("lina", { SlideEditorScreen("Презентація.pptx", state, saving = false, onSave = {}, onClose = {}) }) {
            shot("opened")
            compose.onAllNodesWithText("Новий слайд").onFirst().performClick()
            shot("new-slide")
            assertEquals(before + 1, state.order.size)
            note("lina · new-slide · status: ${compose.onAllNodes(hasText("Слайд ", substring = true)).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }.firstOrNull()}")
        }
    }

    /** Andrii, blind, with a screen reader: every editor on a phone and a tablet looked over, nothing done. */
    @Test fun andriiListensToTheEditors() {
        val word = EditorState(DocxEdit.open(res("docs/04-tables.docx").inputStream())).also { s -> s.focused = s.paras.first { it.text == "Товар" }.key }
        session("andrii-word", { DocxEditorScreen("Таблиці.docx", word, saving = false, onSave = {}, onClose = {}) }) { shot("phone") }
        val edit = XlsxEdit.open(res("sheets/01-budget.xlsx").inputStream())
        val sheet = SheetEditorState(edit, edit.readBack(res("sheets/01-budget.xlsx").inputStream()))
        session("andrii-sheet", { SheetEditorScreen("Бюджет.xlsx", sheet, saving = false, onSave = {}, onClose = {}, startCell = 3 to 1) }) { shot("phone") }
        val slides = SlideEditorState(PptxEdit.open(res("slides/01-project.pptx").inputStream()))
        session("andrii-slides", { SlideEditorScreen("Презентація.pptx", slides, saving = false, onSave = {}, onClose = {}) }) { shot("phone") }
    }

    @Config(qualifiers = "uk-w1200dp-h800dp-mdpi")
    @Test fun andriiListensOnATablet() {
        val word = EditorState(DocxEdit.open(res("docs/04-tables.docx").inputStream())).also { s -> s.focused = s.paras.first { it.text == "Товар" }.key }
        session("andrii-word-tablet", { DocxEditorScreen("Таблиці.docx", word, saving = false, onSave = {}, onClose = {}) }) { shot("tablet") }
        val edit = XlsxEdit.open(res("sheets/01-budget.xlsx").inputStream())
        val sheet = SheetEditorState(edit, edit.readBack(res("sheets/01-budget.xlsx").inputStream()))
        session("andrii-sheet-tablet", { SheetEditorScreen("Бюджет.xlsx", sheet, saving = false, onSave = {}, onClose = {}, startCell = 3 to 1) }) { shot("tablet") }
    }
}
