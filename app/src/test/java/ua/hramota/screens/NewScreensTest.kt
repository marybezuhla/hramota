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
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.hramota.ink.InkPoint
import ua.hramota.ink.MyHandwritingScreen
import ua.hramota.ink.Stroke
import ua.hramota.notebook.NBlock
import ua.hramota.notebook.NoteActivity
import ua.hramota.notebook.NoteState
import ua.hramota.notebook.Notebook
import ua.hramota.notebook.NotebookHome
import ua.hramota.notebook.Span
import ua.hramota.ocr.ScanActivity
import ua.hramota.ocr.ScanPage
import ua.hramota.ocr.ScanScreen
import ua.hramota.social.CardShape
import ua.hramota.social.Cards
import ua.hramota.social.HideEditor
import ua.hramota.social.Pictures
import ua.hramota.social.QuoteCardScreen
import ua.hramota.social.ThreadScreen
import ua.hramota.ui.HramotaTheme
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** The screens new in 0.14: the notebook, a note, one's own handwriting, the scanner, and the tools for social networks. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w400dp-h860dp-xhdpi")
class NewScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                HramotaTheme(dark = dark, day = "nebo", night = "zoriana_nich") {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            compose.mainClock.advanceTimeBy(200)
            Thread.sleep(40)
            compose.waitForIdle()
        }
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Unit
    }

    /** Handwriting that looks like a few written words: loops along a line. */
    private fun scribble(x0: Float, y0: Float, words: Int): List<Stroke> = (0 until words).map { w ->
        val start = x0 + w * 70f
        Stroke((0..60).map { i ->
            val t = i / 60f
            InkPoint(start + t * 55f + 6f * cos(t * 18f), y0 + 9f * sin(t * 18f) - (if (i % 20 < 4) 10f else 0f), i * 12L, 0.4f + 0.3f * sin(t * 6f))
        }, stylus = true)
    }

    private fun notes() {
        Notebook.reload(app)
        if (Notebook.notes(app).value.isNotEmpty()) return
        val work = Notebook.newBook("Робота", Notebook.COLORS[0]).also { Notebook.putBook(app, it) }
        val home = Notebook.newBook("Дім", Notebook.COLORS[1]).also { Notebook.putBook(app, it) }
        val studies = Notebook.newBook("Навчання", Notebook.COLORS[3]).also { Notebook.putBook(app, it) }
        Notebook.create(app, work.id, blocks = listOf(NBlock.Text(1, "Порядок денний", "h2"), NBlock.Text(2, "Бюджет на грудень", "number"), NBlock.Text(3, "Відпустки", "number")), title = "Нарада 13 листопада")
            .let { m -> Notebook.update(app, m.id) { it.copy(pinned = true) } }
        Notebook.create(app, home.id, blocks = listOf(NBlock.Text(1, "Хліб", "check", true), NBlock.Text(2, "Молоко", "check"), NBlock.Text(3, "Яблука", "check")), title = "Купити")
        Notebook.create(app, studies.id, "lines", listOf(NBlock.Ink(1, scribble(20f, 60f, 4), 200f, "конспект лекції"), NBlock.Text(2, "Головне: повторити до п'ятниці")), title = "Історія, лекція 5")
            .let { m -> Notebook.update(app, m.id) { it.copy(starred = true) } }
        Notebook.create(app, null, blocks = listOf(NBlock.Text(1, "Особисте")), title = "Щоденник").let { m -> Notebook.update(app, m.id) { it.copy(locked = true) } }
    }

    @Test fun notebook() {
        notes()
        shot("notebook-list") { NotebookHome(Modifier.fillMaxSize(), emptyList()) {} }
        shot("notebook-list-dark", dark = true) { NotebookHome(Modifier.fillMaxSize(), emptyList()) {} }
    }

    private fun noteState(pen: Boolean) = NoteState(
        ua.hramota.notebook.NoteMeta("demo", title = "Ідеї для проєкту", paper = "grid"),
        listOf(
            NBlock.Text(1, "Що зробити", "h2"),
            NBlock.Text(2, "Записник з рукописом і пошуком", "check", true),
            NBlock.Text(3, "Сканер сторінок", "check"),
            NBlock.Text(4, "Важливо: усе працює без інтернету.", spans = listOf(Span(0, 8, "b"), Span(9, 34, "hl"))),
            NBlock.Ink(5, scribble(16f, 50f, 4) + scribble(16f, 110f, 3), 180f, "ідея"),
            NBlock.Text(6, "Мудрість — у простоті.", "quote"),
        ),
    ).also { it.pen = pen }

    @Test fun note() {
        notes()
        shot("note-text") { NoteScreen(noteState(false)) }
        shot("note-pen") { NoteScreen(noteState(true)) }
        shot("note-dark", dark = true) { NoteScreen(noteState(false)) }
    }

    // (marked as saved: nothing is written while the picture is taken)
    @Composable private fun NoteScreen(s: NoteState) = ua.hramota.notebook.NoteScreen(s, NoteActivity.Holder().also { it.saved = s.version }) {}

    @Test fun myHandwriting() { shot("my-handwriting") { MyHandwritingScreen {} } }

    @Test fun scanner() {
        shot("scan-start") { ScanScreen(ScanActivity.Holder()) {} }
        val held = ScanActivity.Holder()
        val bmp = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFF4F1EA.toInt()) }
        val page = ScanPage(1, File(ua.hramota.ocr.Scans.dir(app), "demo.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }, 600, 800)
        page.reading = false
        page.text = "Шановні колеги!\n\nНагадуємо, що збори відбудуться у вівторок о 18:00 в кабінеті 205."
        held.pages += page
        shot("scan-page") { ScanScreen(held) {} }
    }

    @Test fun social() {
        shot("social-card") { QuoteCardScreen("Хто рано встає, тому Бог дає. А хто пізно лягає, той книжки читає.", "Народна мудрість") {} }
        val card = Cards.render("Мова — це не просто спосіб спілкування, а цілий світ.", "Із записника", CardShape.STORY, Cards.LOOKS[1])
        File("build/screens").apply { mkdirs() }.let { d -> File(d, "social-card-story.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        shot("social-thread") { ThreadScreen(("Сьогодні ми випустили нову версію Грамоти. У ній з'явився записник, де можна писати від руки й шукати навіть у рукописі. " +
            "Ще є сканер: фотографуєте сторінку, і текст розпізнається просто на телефоні, без інтернету. " +
            "А для соцмереж — цитати-картки, ланцюжки дописів і приховування номерів на картинках перед публікацією. ").repeat(2)) {} }
        val shotFile = File(Pictures.dir(app), "demo.png")
        val pic = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b); c.drawColor(android.graphics.Color.WHITE)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f }
            c.drawText("Олена Коваль", 40f, 80f, p); c.drawText("Тел.: +380 67 123 45 67", 40f, 140f, p); c.drawText("olena.k@example.com", 40f, 200f, p)
        }
        shotFile.outputStream().use { pic.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot("social-hide") { HideEditor(shotFile.path) {} }
    }
}
