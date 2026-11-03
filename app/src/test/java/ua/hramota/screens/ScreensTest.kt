package ua.hramota.screens

import ua.hramota.doc.book.BookBlock.Kind as K
import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
import ua.hramota.data.RecentFile
import ua.hramota.diff.Diff
import ua.hramota.doc.Format
import ua.hramota.doc.Pages
import ua.hramota.doc.Paragraph
import ua.hramota.doc.docx.DocxPages
import ua.hramota.doc.docx.DocxReader
import ua.hramota.doc.docx.Fonts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ua.hramota.ui.CompareScreen
import ua.hramota.ui.CompareState
import ua.hramota.ui.HomeScreen
import ua.hramota.ui.SettingsScreen
import ua.hramota.data.AppSettings
import ua.hramota.doc.CsvReader
import ua.hramota.ui.HramotaTheme
import ua.hramota.ui.ViewerScreen
import ua.hramota.ui.ViewerState
import java.io.File

/**
 * Draws the main screens with made-up documents, in Ukrainian and English, light and dark.
 * A screen that crashes fails the build; the pictures go to build/screens for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w400dp-h860dp-xhdpi")
class ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The empty activity that hosts each screen (only known to this test, never to the app)
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    private fun shot(name: String, settle: Int = 8, day: String = ua.hramota.ui.Schemes.DEFAULT, night: String = ua.hramota.ui.Schemes.DEFAULT, content: @Composable () -> Unit) = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                HramotaTheme(day = day, night = night) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        // Let delays (search waits for typing to pause) and background work finish
        repeat(settle) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            // Delays in effects (search waits 300 ms for typing to pause) run on the test clock
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

    private val now = 1_793_500_000_000L
    private val recentsUk = listOf(
        RecentFile("content://demo/1", "Договір поставки v2.docx", now),
        RecentFile("content://demo/2", "Договір поставки v1.docx", now - 86_400_000L),
        RecentFile("content://demo/3", "Звіт за жовтень.pdf", now - 3 * 86_400_000L),
        RecentFile("content://demo/4", "Протокол зустрічі.odt", now - 5 * 86_400_000L),
        RecentFile("content://demo/5", "Бюджет родини 2026.xlsx", now - 9 * 86_400_000L),
        RecentFile("content://demo/6", "Нотатки.md", now - 12 * 86_400_000L),
    )
    private val recentsEn = listOf(
        RecentFile("content://demo/1", "Supply contract v2.docx", now),
        RecentFile("content://demo/2", "Supply contract v1.docx", now - 86_400_000L),
        RecentFile("content://demo/3", "October report.pdf", now - 3 * 86_400_000L),
        RecentFile("content://demo/4", "Meeting notes.odt", now - 5 * 86_400_000L),
    )

    @Composable
    private fun home(list: List<RecentFile>, prompt: String? = null) =
        HomeScreen(list, prompt, onOpenFile = {}, onCompare = {}, onOpenRecent = {}, onRemove = {}, onClear = {}, now = now)

    private val contractV1 = listOf(
        "Договір поставки № 12",
        "1. Предмет договору",
        "Постачальник зобов'язується передати Покупцеві товар згідно зі специфікацією, а Покупець — прийняти й оплатити його.",
        "2. Строки й оплата",
        "Покупець оплачує товар протягом 10 банківських днів після підписання акта приймання.",
        "Передоплата не передбачена.",
        "3. Відповідальність",
        "За прострочення оплати Покупець сплачує пеню в розмірі 0,1 % за кожен день.",
        "4. Інші умови",
        "Договір діє до 31 грудня 2026 року.",
    )
    private val contractV2 = listOf(
        "Договір поставки № 12",
        "1. Предмет договору",
        "Постачальник зобов'язується передати Покупцеві товар згідно зі специфікацією, а Покупець — прийняти й оплатити його.",
        "2. Строки й оплата",
        "Покупець оплачує товар протягом 5 банківських днів після підписання акта приймання.",
        "Покупець вносить передоплату 50 % вартості товару.",
        "3. Відповідальність",
        "4. Інші умови",
        "Договір діє до 31 грудня 2027 року з правом продовження.",
    )
    private val contractEnV1 = listOf(
        "Supply contract No. 12",
        "2. Terms and payment",
        "The buyer pays for the goods within 10 banking days after signing the acceptance report.",
        "No prepayment is required.",
        "3. Liability",
        "For late payment the buyer pays a penalty of 0.1% per day.",
    )
    private val contractEnV2 = listOf(
        "Supply contract No. 12",
        "2. Terms and payment",
        "The buyer pays for the goods within 5 banking days after signing the acceptance report.",
        "The buyer makes a 50% prepayment.",
        "3. Liability",
    )

    private val document = listOf(
        Paragraph("Протокол зустрічі", 1),
        Paragraph("Дата: 28 жовтня. Учасники: команда проєкту."),
        Paragraph("Рішення", 2),
        Paragraph("1. Запустити тестову версію до кінця листопада."),
        Paragraph("2. Підготувати шаблони рахунку та акта виконаних робіт."),
        Paragraph("3. Зібрати набір документів для перевірки точності відображення."),
        Paragraph("Хто що робить", 2),
        Paragraph("Дизайн екранів — до 10 листопада. Тексти довідки — до 15 листопада. Перевірка на трьох телефонах — до 20 листопада."),
        Paragraph("Наступна зустріч — за тиждень, у той самий час."),
    )

    @Test fun homeEmpty() = shot("01-home-empty") { home(emptyList()) }
    @Test fun homeRecent() = shot("02-home") { home(recentsUk) }
    @Config(qualifiers = "+night")
    @Test fun homeDark() = shot("02-home-dark") { home(recentsUk) }
    @Test fun homeComparing() = shot("03-home-compare-second") { home(recentsUk, "Тепер оберіть нову версію") }
    @Config(qualifiers = "en-w400dp-h860dp-xhdpi")
    @Test fun homeEnglish() = shot("02-home-en") { home(recentsEn) }
    // The colours of pysanky: each day scheme by day, each night scheme at night
    @Test fun homeKreyda() = shot("02-home-kreyda", day = "kreyda") { home(recentsUk) }
    @Test fun homeVisk() = shot("02-home-visk", day = "visk") { home(recentsUk) }
    @Test fun homeNebo() = shot("02-home-nebo", day = "nebo") { home(recentsUk) }
    @Config(qualifiers = "+night")
    @Test fun homePodillia() = shot("02-home-podillia", night = "podillia") { home(recentsUk) }
    @Config(qualifiers = "+night")
    @Test fun homeHutsulshchyna() = shot("02-home-hutsulshchyna", night = "hutsulshchyna") { home(recentsUk) }
    @Config(qualifiers = "+night")
    @Test fun homeChornozem() = shot("02-home-chornozem", night = "chornozem") { home(recentsUk) }
    @Config(qualifiers = "+night")
    @Test fun settingsHutsulshchyna() = shot("09-settings-hutsulshchyna", night = "hutsulshchyna") { SettingsScreen(AppSettings(nightScheme = "hutsulshchyna"), "0.4.0", onChange = {}, onClearRecent = {}, onBack = {}) }

    @Test fun viewerText() = shot("04-viewer-reading") { ViewerScreen("Протокол зустрічі.docx", ViewerState.Text(document, Format.DOCX), onBack = {}) }
    @Config(qualifiers = "+night")
    @Test fun viewerTextDark() = shot("04-viewer-reading-dark") { ViewerScreen("Протокол зустрічі.docx", ViewerState.Text(document, Format.DOCX), onBack = {}) }
    /** A test document's pages, drawn right away so the picture is ready when the screen is captured. */
    private fun docxPages(name: String): Pages {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(javaClass.classLoader!!.getResource("docs/$name")!!.toURI())
        val pages = DocxPages(file.inputStream().use { DocxReader.read(it) }, Fonts(app.assets))
        return object : ua.hramota.doc.TextPages {
            override val pageCount = pages.pageCount
            override fun aspect(index: Int) = pages.aspect(index)
            override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = pages.draw(index, widthPx)?.asImageBitmap()
            override suspend fun pageText(index: Int) = pages.pageText(index)
            override suspend fun boxes(index: Int, start: Int, end: Int) = pages.boxes(index, start, end)
            override suspend fun charAt(index: Int, x: Float, y: Float) = pages.charAt(index, x, y)
        }
    }

    @Test fun viewerPages() = shot("04-viewer-docx-pages") {
        ViewerScreen("Договір поставки.docx", ViewerState.Paged(docxPages("05-flow.docx"), emptyList()), onBack = {}, bookmarks = setOf(0))
    }
    @Test fun viewerInspector() = shot("04-viewer-inspector") {
        val risky = ua.hramota.doc.Inspection(macros = true, embedded = listOf("oleObject1.bin"), remoteResources = 1, links = 3, trackedChanges = true, author = "Автор документа")
        ViewerScreen("Рахунок.docx", ViewerState.Paged(docxPages("04-tables.docx"), emptyList(), risky), onBack = {})
    }
    @Test fun viewerInspectorQuiet() = shot("04-viewer-inspector-info") {
        val info = ua.hramota.doc.Inspection(comments = 2, trackedChanges = true)
        ViewerScreen("Протокол.docx", ViewerState.Paged(docxPages("03-lists.docx"), emptyList(), info), onBack = {})
    }
    @Test fun viewerSearch() = shot("04-viewer-search", settle = 40) {
        ViewerScreen("Договір поставки.docx", ViewerState.Paged(docxPages("05-flow.docx"), emptyList()), onBack = {}, startSearch = "розділ договору")
    }
    @Test fun viewerOverview() = shot("04-viewer-overview") {
        ViewerScreen("Договір поставки.docx", ViewerState.Paged(docxPages("05-flow.docx"), emptyList()), onBack = {}, bookmarks = setOf(0, 3), startInOverview = true)
    }
    @Config(qualifiers = "+night")
    @Test fun viewerOverviewDark() = shot("04-viewer-overview-dark") {
        ViewerScreen("Договір поставки.docx", ViewerState.Paged(docxPages("05-flow.docx"), emptyList()), onBack = {}, bookmarks = setOf(2), startInOverview = true)
    }
    @Config(qualifiers = "+night")
    @Test fun viewerPagesDark() = shot("04-viewer-docx-pages-dark") {
        ViewerScreen("Таблиці.docx", ViewerState.Paged(docxPages("04-tables.docx"), emptyList()), onBack = {})
    }

    @Test fun viewerUnsupported() = shot("05-viewer-unsupported") { ViewerScreen("Презентація проєкту.pptx", ViewerState.Unsupported(Format.PPTX), onBack = {}) }
    @Test fun viewerLoading() = shot("05-viewer-loading") { ViewerScreen("Звіт за жовтень.pdf", ViewerState.Loading, onBack = {}) }

    @Test fun compare() = shot("06-compare") {
        CompareScreen("Договір поставки v1.docx", "Договір поставки v2.docx", CompareState.Ready(Diff.compare(contractV1, contractV2)), onBack = {})
    }
    @Config(qualifiers = "+night")
    @Test fun compareDark() = shot("06-compare-dark") {
        CompareScreen("Договір поставки v1.docx", "Договір поставки v2.docx", CompareState.Ready(Diff.compare(contractV1, contractV2)), onBack = {})
    }
    @Config(qualifiers = "en-w400dp-h860dp-xhdpi")
    @Test fun compareEnglish() = shot("06-compare-en") {
        CompareScreen("Supply contract v1.docx", "Supply contract v2.docx", CompareState.Ready(Diff.compare(contractEnV1, contractEnV2)), onBack = {})
    }
    @Test fun compareSame() = shot("07-compare-same") {
        CompareScreen("Нотатки.md", "Нотатки (копія).md", CompareState.Ready(Diff.compare(listOf("Однаковий текст"), listOf("Однаковий текст"))), onBack = {})
    }
    // A large system font must not break the layout
    @Config(fontScale = 1.5f)
    @Test fun compareLargeFont() = shot("08-compare-large-font") {
        CompareScreen("Договір поставки v1.docx", "Договір поставки v2.docx", CompareState.Ready(Diff.compare(contractV1, contractV2)), onBack = {})
    }

    @Composable
    private fun settings() = SettingsScreen(AppSettings(), "0.4.0", onChange = {}, onClearRecent = {}, onBack = {})
    @Test fun settingsScreen() = shot("09-settings") { settings() }
    @Config(qualifiers = "+night")
    @Test fun settingsDark() = shot("09-settings-dark") { settings() }
    @Config(qualifiers = "en-w400dp-h860dp-xhdpi")
    @Test fun settingsEnglish() = shot("09-settings-en") { settings() }
    @Config(fontScale = 1.5f)
    @Test fun settingsLargeFont() = shot("09-settings-large-font") { settings() }

    private val budgetCsv = """Місяць;Продукти;Транспорт;Комунальні;Разом
Січень;6200;1100;3400;10700
Лютий;5900;1050;3600;10550
Березень;6400;1200;2900;10500
Квітень;6100;980;2100;9180
Травень;6600;1300;1800;9700
"Червень, з відпусткою";9800;4200;1700;15700"""
    @Test fun viewerCsv() = shot("10-viewer-csv") { ViewerScreen("Витрати 2026.csv", ViewerState.Table(CsvReader.parse(budgetCsv)), onBack = {}) }
    @Config(qualifiers = "+night")
    @Test fun viewerCsvDark() = shot("10-viewer-csv-dark") { ViewerScreen("Витрати 2026.csv", ViewerState.Table(CsvReader.parse(budgetCsv)), onBack = {}) }
    @Test fun viewerCsvSearch() = shot("10-viewer-csv-search", settle = 40) { ViewerScreen("Витрати 2026.csv", ViewerState.Table(CsvReader.parse(budgetCsv)), onBack = {}, startSearch = "2100") }
    @Test fun viewerTextSearch() = shot("04-viewer-reading-search", settle = 40) { ViewerScreen("Протокол зустрічі.docx", ViewerState.Text(document, Format.DOCX), onBack = {}, startSearch = "листопада") }

    private fun budget() = ua.hramota.doc.sheet.XlsxReader.read(File(javaClass.classLoader!!.getResource("sheets/01-budget.xlsx")!!.toURI()).inputStream())
    @Test fun viewerSheet() = shot("11-viewer-sheet") { ViewerScreen("Сімейний бюджет.xlsx", ViewerState.Sheets(budget()), onBack = {}) }
    @Config(qualifiers = "+night")
    @Test fun viewerSheetDark() = shot("11-viewer-sheet-dark") { ViewerScreen("Сімейний бюджет.xlsx", ViewerState.Sheets(budget()), onBack = {}) }
    @Test fun viewerSheetSearch() = shot("11-viewer-sheet-search", settle = 40) { ViewerScreen("Сімейний бюджет.xlsx", ViewerState.Sheets(budget()), onBack = {}, startSearch = "квітень") }

    // The launcher icon as a phone shows it: background and foreground in a circle, and the one-colour themed version
    @Test fun launcherIcon() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val size = 432
        val out = Bitmap.createBitmap(size * 2 + 40, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFF1C1B1F.toInt())
        for ((k, fg) in listOf(ua.hramota.R.drawable.ic_launcher_foreground, ua.hramota.R.drawable.ic_launcher_monochrome).withIndex()) {
            canvas.save()
            canvas.translate(k * (size + 40f), 0f)
            val path = android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 3f, android.graphics.Path.Direction.CW) }
            canvas.clipPath(path)
            if (k == 0) app.getDrawable(ua.hramota.R.drawable.ic_launcher_background)!!.apply { setBounds(0, 0, size, size) }.draw(canvas)
            else canvas.drawColor(0xFFD8E2FF.toInt())
            val d = app.getDrawable(fg)!!
            if (k == 1) d.setTint(0xFF0F3A70.toInt())
            d.setBounds(0, 0, size, size)
            d.draw(canvas)
            canvas.restore()
        }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "00-icon.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val shelfEntries = listOf(
        ua.hramota.data.LibraryEntry("a1", "Порівняльна таблиця до проєкту закону.docx", "DOCX", null, position = 36, total = 148, bookmarks = setOf(3, 80), copy = "x", shelves = setOf("work")),
        ua.hramota.data.LibraryEntry("a2", "Звіт за жовтень.pdf", "PDF", null, position = 2, total = 12, copy = "x", shelves = setOf("work")),
        ua.hramota.data.LibraryEntry("a3", "Сімейний бюджет.xlsx", "XLSX", null, copy = "x", shelves = setOf("fam")),
        ua.hramota.data.LibraryEntry("a4", "Кобзар.epub", "EPUB", null, position = 120, total = 600, copy = "x", shelves = setOf("read")),
        ua.hramota.data.LibraryEntry("a5", "Презентація проєкту.pptx", "PPTX", null, position = 3, total = 14, copy = "x", shelves = setOf("work")),
    )
    @Composable private fun comparePicker(title: String) {
        androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) {
                androidx.compose.material3.Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                ua.hramota.ui.ComparePickerList(shelfEntries, recentsUk, onShelf = {}, onRecent = {}, onOther = {})
            }
        }
    }
    @Test fun comparePickerScreen() = shot("06-compare-pick") { comparePicker("Оберіть попередню версію") }
    @Config(qualifiers = "+night")
    @Test fun comparePickerDark() = shot("06-compare-pick-dark") { comparePicker("Оберіть попередню версію") }
    private fun editor(name: String) = ua.hramota.ui.EditorState(
        ua.hramota.doc.docx.edit.DocxEdit.open(File(javaClass.classLoader!!.getResource(if ('/' in name) name else "docs/$name")!!.toURI()).inputStream()),
    ).also { s -> s.focused = s.paras.first { it.text.startsWith("Другий") || it.text.length > 30 }.key }
    @Test fun editorLists() = shot("13-edit-docx") { ua.hramota.ui.DocxEditorScreen("Списки.docx", androidx.compose.runtime.remember { editor("03-lists.docx") }, saving = false, onSave = {}, onClose = {}) }
    @Config(qualifiers = "+night")
    @Test fun editorDark() = shot("13-edit-docx-dark") { ua.hramota.ui.DocxEditorScreen("Таблиці.docx", androidx.compose.runtime.remember { editor("04-tables.docx") }, saving = false, onSave = {}, onClose = {}) }
    @Test fun editorComments() = shot("13-edit-docx-comments") { ua.hramota.ui.DocxEditorScreen("Коментарі.docx", androidx.compose.runtime.remember { editor("docs-edit/comments.docx") }, saving = false, onSave = {}, onClose = {}) }
    @Config(qualifiers = "+night")
    @Test fun editorCommentsDark() = shot("13-edit-docx-comments-dark") { ua.hramota.ui.DocxEditorScreen("Коментарі.docx", androidx.compose.runtime.remember { editor("docs-edit/comments.docx") }, saving = false, onSave = {}, onClose = {}) }
    @Config(fontScale = 1.5f)
    @Test fun editorLargeFont() = shot("13-edit-docx-large-font") { ua.hramota.ui.DocxEditorScreen("Списки.docx", androidx.compose.runtime.remember { editor("03-lists.docx") }, saving = false, onSave = {}, onClose = {}) }
    private fun sheetEditor() = ua.hramota.ui.SheetEditorState(
        ua.hramota.doc.sheet.edit.XlsxEdit.open(File(javaClass.classLoader!!.getResource("sheets/01-budget.xlsx")!!.toURI()).inputStream()), budget())
    @Test fun sheetEditorScreen() = shot("14-edit-xlsx") { ua.hramota.ui.SheetEditorScreen("Сімейний бюджет.xlsx", androidx.compose.runtime.remember { sheetEditor() }, saving = false, onSave = {}, onClose = {}, startCell = 3 to 4) }
    @Config(qualifiers = "+night")
    @Test fun sheetEditorDark() = shot("14-edit-xlsx-dark") { ua.hramota.ui.SheetEditorScreen("Сімейний бюджет.xlsx", androidx.compose.runtime.remember { sheetEditor() }, saving = false, onSave = {}, onClose = {}, startCell = 3 to 1) }
    @Test fun slideEditorScreen() = shot("15-edit-pptx") {
        val st = androidx.compose.runtime.remember {
            ua.hramota.ui.SlideEditorState(ua.hramota.doc.slides.edit.PptxEdit.open(File(javaClass.classLoader!!.getResource("slides/01-project.pptx")!!.toURI()).inputStream()))
        }
        ua.hramota.ui.SlideEditorScreen("Презентація проєкту.pptx", st, saving = false, onSave = {}, onClose = {})
    }
    @Config(fontScale = 1.5f)
    @Test fun sheetEditorLargeFont() = shot("14-edit-xlsx-large-font") { ua.hramota.ui.SheetEditorScreen("Сімейний бюджет.xlsx", androidx.compose.runtime.remember { sheetEditor() }, saving = false, onSave = {}, onClose = {}, startCell = 3 to 4) }
    @Config(qualifiers = "en-w400dp-h860dp-xhdpi")
    @Test fun sheetEditorEnglish() = shot("14-edit-xlsx-en") { ua.hramota.ui.SheetEditorScreen("Family budget.xlsx", androidx.compose.runtime.remember { sheetEditor() }, saving = false, onSave = {}, onClose = {}, startCell = 3 to 4) }
    @Config(fontScale = 1.5f)
    @Test fun slideEditorLargeFont() = shot("15-edit-pptx-large-font") {
        val st = androidx.compose.runtime.remember {
            ua.hramota.ui.SlideEditorState(ua.hramota.doc.slides.edit.PptxEdit.open(File(javaClass.classLoader!!.getResource("slides/01-project.pptx")!!.toURI()).inputStream()))
        }
        ua.hramota.ui.SlideEditorScreen("Презентація проєкту.pptx", st, saving = false, onSave = {}, onClose = {})
    }
    @Config(fontScale = 1.5f)
    @Test fun homeLargeFont() = shot("02-home-large-font") { home(recentsUk) }
    // Reading aloud: the player under the document, while a sentence is being read
    private val reading = ua.hramota.speech.ReadAloud.State(document = "Протокол зустрічі.docx", title = "Протокол зустрічі", playing = true, unit = 1, start = 0, end = 40, speed = 1f, sleepAt = ua.hramota.speech.ReadAloud.SLEEP_CHAPTER)
    @Composable private fun readingScreen(state: ua.hramota.speech.ReadAloud.State) = androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        ViewerScreen("Протокол зустрічі.docx", ViewerState.Text(document, Format.DOCX), onBack = {})
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) { ua.hramota.ui.ReadAloudBar(state, onBookmark = {}, onSettings = {}) }
    }
    @Test fun readAloud() = shot("15-read-aloud") { readingScreen(reading) }
    @Config(qualifiers = "+night", fontScale = 1.5f)
    @Test fun readAloudDark() = shot("15-read-aloud-dark") { readingScreen(reading.copy(playing = false, notice = ua.hramota.speech.ReadAloud.NOTICE_NO_LANGUAGE)) }
    @Config(qualifiers = "en-w400dp-h860dp-xhdpi")
    @Test fun readAloudEnglish() = shot("15-read-aloud-en") { readingScreen(reading.copy(preparing = true, sleepAt = null)) }

    @Config(qualifiers = "en-w400dp-h860dp-xhdpi")
    @Test fun editorEnglish() = shot("13-edit-docx-en") { ua.hramota.ui.DocxEditorScreen("Contract.docx", androidx.compose.runtime.remember { editor("05-flow.docx") }, saving = false, onSave = {}, onClose = {}) }
    @Test fun homeShelf() = shot("12-home-shelf") {
        HomeScreen(recentsUk, null, onOpenFile = {}, onCompare = {}, onOpenRecent = {}, onRemove = {}, onClear = {}, shelf = shelfEntries, startOnShelf = true, now = now)
    }
    @Test fun homeShelfWood() = shot("12-home-shelf-wood") {
        HomeScreen(recentsUk, null, onOpenFile = {}, onCompare = {}, onOpenRecent = {}, onRemove = {}, onClear = {}, shelf = shelfEntries, shelves = shelfDefs(allStyle = "wood"), startOnShelf = true, now = now)
    }
    @Config(qualifiers = "+night")
    @Test fun homeShelfWoodDark() = shot("12-home-shelf-wood-dark") {
        HomeScreen(recentsUk, null, onOpenFile = {}, onCompare = {}, onOpenRecent = {}, onRemove = {}, onClear = {}, shelf = shelfEntries, shelves = shelfDefs(allStyle = "wood"), startOnShelf = true, now = now)
    }
    private fun shelfDefs(allStyle: String = "modern") = listOf(
        ua.hramota.data.ShelfDef(ua.hramota.data.ShelfDef.ALL, "", allStyle),
        ua.hramota.data.ShelfDef("work", "Робота", "list", 210f, 1),
        ua.hramota.data.ShelfDef("read", "Читаю", "wood", 0f, 2),
        ua.hramota.data.ShelfDef("fam", "Сім'я", "modern", 120f, 3),
    )
    @Test fun homeShelves() = shot("12-home-shelves") {
        HomeScreen(recentsUk, null, onOpenFile = {}, onCompare = {}, onOpenRecent = {}, onRemove = {}, onClear = {}, shelf = shelfEntries, shelves = shelfDefs(), startOnShelf = true, now = now)
    }
    @Test fun homeShelfList() = shot("12-home-shelf-list") {
        HomeScreen(recentsUk, null, onOpenFile = {}, onCompare = {}, onOpenRecent = {}, onRemove = {}, onClear = {}, shelf = shelfEntries, shelves = shelfDefs(), startOnShelf = true, startShelf = "work", now = now)
    }
    @Config(qualifiers = "+night")
    @Test fun homeShelfRedWoodDark() = shot("12-home-shelf-wood-red-dark") {
        HomeScreen(recentsUk, null, onOpenFile = {}, onCompare = {}, onOpenRecent = {}, onRemove = {}, onClear = {}, shelf = shelfEntries.map { it.copy(shelves = setOf("read")) }, shelves = shelfDefs(), startOnShelf = true, startShelf = "read", now = now)
    }
    // Dialogs open in their own window, which screenshots here do not wait for reliably

    // A made-up book: chapters, an epigraph, styles and a poem
    private fun book(): ua.hramota.doc.book.Book {
        fun t(text: String, kind: ua.hramota.doc.book.BookBlock.Kind = K.PARAGRAPH, vararg spans: ua.hramota.doc.book.Span) = ua.hramota.doc.book.BookBlock.Text(text, spans.toList(), kind)
        val p1 = "Над річкою стояв старий млин, і кожного ранку мірошник відчиняв його важкі двері ще до схід сонця."
        return ua.hramota.doc.book.Book("Казка про млин", "Олена Вигадана", listOf(
            t("Розділ перший", K.HEADING1),
            t("Хто рано встає, тому й бог дає.", K.QUOTE),
            t(p1, K.PARAGRAPH, ua.hramota.doc.book.Span(p1.indexOf("старий млин"), p1.indexOf("старий млин") + 11, italic = true)),
            t("Вода шуміла під колесом, а в повітрі пахло борошном і свіжим хлібом. Діти з усього села прибігали подивитися, як крутяться жорна, і мірошник щоразу розповідав їм нову історію."),
            t("— Колись, — казав він, — цей млин молов не зерно, а зорі.", K.PARAGRAPH, ua.hramota.doc.book.Span(0, 0)),
            t("* * *", K.SUBTITLE),
            t("Тихо крутиться колесо,", K.POEM), t("тихо ллється вода,", K.POEM), t("і над млином до ранку", K.POEM), t("світить зірка руда.", K.POEM),
            t("Розділ другий", K.HEADING1),
            t("Наступного дня мірошник прокинувся раніше, ніж звичайно."),
        ), emptyMap(), null)
    }
    @Test fun viewerBook() = shot("13-viewer-book") { ViewerScreen("Казка про млин.epub", ViewerState.Reader(book()), onBack = {}) }
    @Config(qualifiers = "+night")
    @Test fun viewerBookDark() = shot("13-viewer-book-dark") { ViewerScreen("Казка про млин.epub", ViewerState.Reader(book()), onBack = {}) }
    @Config(fontScale = 1.6f)
    @Test fun viewerBookLargeFont() = shot("13-viewer-book-large-font") { ViewerScreen("Казка про млин.epub", ViewerState.Reader(book()), onBack = {}) }
    private val bookNotes = listOf(
        ua.hramota.data.Note(2, 0, 2, 26, 0, "Початок, який хочеться перечитати.", at = 1),
        ua.hramota.data.Note(3, 0, 3, 46, 2, at = 2),
    )
    @Test fun viewerBookContents() = shot("13-viewer-book-contents") { ViewerScreen("Казка про млин.epub", ViewerState.Reader(book()), onBack = {}, startInOverview = true, bookmarks = setOf(3), notes = bookNotes) }
    @Test fun viewerBookNotes() = shot("13-viewer-book-notes") { ViewerScreen("Казка про млин.epub", ViewerState.Reader(book()), onBack = {}, notes = bookNotes) }
    @Test fun viewerBookNotesNight() = shot("13-viewer-book-notes-night") { ViewerScreen("Казка про млин.epub", ViewerState.Reader(book()), onBack = {}, notes = bookNotes, readSettings = ua.hramota.data.AppSettings(readPaper = "night")) }
    @Test fun viewerBookScroll() = shot("13-viewer-book-scroll") { ViewerScreen("Казка про млин.epub", ViewerState.Reader(book()), onBack = {}, readSettings = ua.hramota.data.AppSettings(readPaged = false)) }
    @Test fun viewerBookSepia() = shot("13-viewer-book-sepia") { ViewerScreen("Казка про млин.epub", ViewerState.Reader(book()), onBack = {}, readSettings = ua.hramota.data.AppSettings(readPaper = "sepia")) }
    @Test fun viewerBookNight() = shot("13-viewer-book-night") { ViewerScreen("Казка про млин.epub", ViewerState.Reader(book()), onBack = {}, readSettings = ua.hramota.data.AppSettings(readPaper = "night")) }
    @Test fun readingControls() = shot("13-reading-controls") { androidx.compose.material3.Surface { ua.hramota.ui.ReadingControls(ua.hramota.data.AppSettings(), {}) } }
    @Config(qualifiers = "+night")
    @Test fun readingControlsDark() = shot("13-reading-controls-dark") { androidx.compose.material3.Surface { ua.hramota.ui.ReadingControls(ua.hramota.data.AppSettings(readTurn = "curl"), {}) } }
    @Test fun lockScreen() = shot("14-lock") { ua.hramota.ui.LockScreen(onUnlock = {}) }
    @Config(qualifiers = "+night")
    @Test fun lockScreenKeyLost() = shot("14-lock-key-lost-dark") { ua.hramota.ui.LockScreen(keyLost = true, onUnlock = {}) }
    @Test fun viewerPagesNotes() = shot("15-viewer-pages-notes", settle = 40) {
        ViewerScreen("Договір поставки.docx", ViewerState.Paged(docxPages("05-flow.docx"), emptyList()), onBack = {},
            notes = listOf(ua.hramota.data.Note(0, 30, 0, 120, 0, "Перевірити строки", at = 1), ua.hramota.data.Note(0, 200, 0, 260, 2, at = 2)))
    }
}
