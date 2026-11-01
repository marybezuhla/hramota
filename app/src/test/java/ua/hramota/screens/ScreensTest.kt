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
import ua.hramota.data.RecentFile
import ua.hramota.diff.Diff
import ua.hramota.doc.Format
import ua.hramota.doc.Paragraph
import ua.hramota.ui.CompareScreen
import ua.hramota.ui.CompareState
import ua.hramota.ui.HomeScreen
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

    private fun shot(name: String, content: @Composable () -> Unit) = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                HramotaTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
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

    private fun home(list: List<RecentFile>, prompt: String? = null) =
        HomeScreen(list, prompt, onOpenFile = {}, onCompare = {}, onOpenRecent = {}, onRemove = {}, onClear = {})

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

    @Test fun viewerText() = shot("04-viewer-docx") { ViewerScreen("Протокол зустрічі.docx", ViewerState.Text(document, Format.DOCX), onBack = {}) }
    @Config(qualifiers = "+night")
    @Test fun viewerTextDark() = shot("04-viewer-docx-dark") { ViewerScreen("Протокол зустрічі.docx", ViewerState.Text(document, Format.DOCX), onBack = {}) }
    @Test fun viewerUnsupported() = shot("05-viewer-unsupported") { ViewerScreen("Бюджет родини 2026.xlsx", ViewerState.Unsupported(Format.XLSX), onBack = {}) }
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
}
