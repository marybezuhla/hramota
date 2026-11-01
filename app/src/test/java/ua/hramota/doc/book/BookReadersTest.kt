package ua.hramota.doc.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.Format
import ua.hramota.doc.TextExtractor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Made-up books in EPUB and FB2: chapters, styles, poems, pictures and covers read as a reader shows them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BookReadersTest {
    // A 1×1 PNG
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==")

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in files) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun epub(): ByteArray = zip(
        "mimetype" to "application/epub+zip".toByteArray(),
        "META-INF/container.xml" to """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title>Вигадана книга</dc:title><dc:creator>Олена Вигадана</dc:creator><meta name="cover" content="cov"/></metadata>
<manifest><item id="cov" href="img/cover.png" media-type="image/png"/><item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
<item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/><item id="skip" href="text/skip.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="c1"/><itemref idref="skip" linear="no"/><itemref idref="c2"/></spine></package>""".toByteArray(),
        "OEBPS/img/cover.png" to png,
        "OEBPS/text/ch1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><style>p{}</style></head><body>
<h1>Розділ перший</h1><p>Перший    абзац
 з <b>жирним</b> і <i>похилим</i> словом&nbsp;— і виноскою<sup>1</sup>.</p>
<p>Рядок<br/>другий рядок</p><blockquote><p>Цитата мудра.</p></blockquote>
<div><img src="../img/cover.png" alt=""/></div><hr/><p>Після розриву &laquo;лапки&raquo;&hellip;</p></body></html>""".toByteArray(),
        "OEBPS/text/skip.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Не в основному порядку</p></body></html>""".toByteArray(),
        "OEBPS/text/ch 2.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><h2>Другий розділ</h2><p>Кінець.</p></body></html>""".toByteArray(),
    )

    @Test fun readsEpub() {
        val book = EpubReader.read(ByteArrayInputStream(epub()))
        assertEquals("Вигадана книга", book.title)
        assertEquals("Олена Вигадана", book.author)
        assertEquals("OEBPS/img/cover.png", book.cover)
        val texts = book.blocks.filterIsInstance<BookBlock.Text>()
        assertEquals(BookBlock.Kind.HEADING1, texts[0].kind)
        assertEquals("Розділ перший", texts[0].text)
        val first = texts[1]
        assertEquals("Перший абзац з жирним і похилим словом\u00A0— і виноскою1.", first.text)
        val bold = first.spans.single { it.bold }
        assertEquals("жирним", first.text.substring(bold.start, bold.end))
        assertEquals("похилим", first.text.substring(first.spans.single { it.italic }.let { it.start until it.end }))
        assertEquals("1", first.text.substring(first.spans.single { it.raised }.let { it.start until it.end }))
        assertEquals("Рядок\nдругий рядок", texts[2].text)
        assertEquals(BookBlock.Kind.QUOTE, texts.single { it.text == "Цитата мудра." }.kind)
        assertTrue(book.blocks.any { it is BookBlock.Image && it.key == "OEBPS/img/cover.png" })
        assertTrue(texts.any { it.kind == BookBlock.Kind.BREAK })
        assertTrue(texts.any { it.text == "Після розриву «лапки»…" })
        assertEquals(BookBlock.Kind.HEADING2, texts.single { it.text == "Другий розділ" }.kind)
        assertFalse("not in reading order", texts.any { it.text.contains("Не в основному") })
        // Head and style text stay out
        assertFalse(texts.any { it.text == "x" || it.text.contains("p{}") })
        // The text for comparing and the format's detection
        val paras = TextExtractor.read(ByteArrayInputStream(epub()), Format.EPUB)
        assertEquals(1, paras.first().heading)
        assertEquals(Format.EPUB, Format.detect("книга.epub", null))
    }

    private fun fb2(encoding: String = "UTF-8", withImage: Boolean = true) = """<?xml version="1.0" encoding="$encoding"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info><author><first-name>Іван</first-name><last-name>Вигаданий</last-name></author>
<book-title>Казка про ґудзик</book-title><annotation><p>Анотація не в тексті</p></annotation>
<coverpage><image l:href="#cover.png"/></coverpage></title-info></description>
<body><title><p>Казка про ґудзик</p></title>
<epigraph><p>Мудре слово.</p><text-author>Хтось</text-author></epigraph>
<section><title><p>Частина 1</p></title><p>Жив собі <strong>ґудзик</strong>, <emphasis>маленький</emphasis>.</p>
<empty-line/><poem><stanza><v>Перший рядок вірша,</v><v>другий рядок вірша.</v></stanza></poem>
<subtitle>* * *</subtitle>${if (withImage) "<image l:href=\"#cover.png\"/>" else ""}
<section><title><p>Підрозділ</p></title><p>Глибше.</p></section></section></body>
<binary id="cover.png" content-type="image/png">${Base64.getEncoder().encodeToString(png)}</binary></FictionBook>"""

    @Test fun readsFb2() {
        val book = Fb2Reader.read(ByteArrayInputStream(fb2().toByteArray()))
        assertEquals("Казка про ґудзик", book.title)
        assertEquals("Іван Вигаданий", book.author)
        assertEquals("cover.png", book.cover)
        assertNotNull(book.images["cover.png"])
        val texts = book.blocks.filterIsInstance<BookBlock.Text>()
        assertFalse(texts.any { it.text.contains("Анотація") })
        assertEquals(BookBlock.Kind.HEADING1, texts.first().kind)
        assertEquals(BookBlock.Kind.QUOTE, texts.single { it.text == "Мудре слово." }.kind)
        assertEquals(BookBlock.Kind.HEADING1, texts.single { it.text == "Частина 1" }.kind)
        assertEquals(BookBlock.Kind.HEADING2, texts.single { it.text == "Підрозділ" }.kind)
        assertEquals(BookBlock.Kind.POEM, texts.single { it.text == "Перший рядок вірша," }.kind)
        assertEquals(BookBlock.Kind.SUBTITLE, texts.single { it.text == "* * *" }.kind)
        val p = texts.single { it.text.startsWith("Жив") }
        assertEquals("ґудзик", p.text.substring(p.spans.single { it.bold }.let { it.start until it.end }))
        assertEquals("маленький", p.text.substring(p.spans.single { it.italic }.let { it.start until it.end }))
        assertTrue(book.blocks.any { it is BookBlock.Image })
        assertEquals(BookBlock.Kind.PARAGRAPH, texts.single { it.text == "Глибше." }.kind)
    }

    @Test fun readsFb2InOldEncodingAndZip() {
        val book = Fb2Reader.read(ByteArrayInputStream(fb2("windows-1251").toByteArray(charset("windows-1251"))))
        assertEquals("Казка про ґудзик", book.title)
        val zipped = zip("kazka.fb2" to fb2().toByteArray())
        assertEquals("Іван Вигаданий", Fb2Reader.read(ByteArrayInputStream(zipped)).author)
        assertEquals(Format.FB2, Format.detect("kazka.fb2.zip", null))
        // Text for comparing reads no pictures
        assertTrue(Fb2Reader.read(ByteArrayInputStream(fb2().toByteArray()), withImages = false).images.isEmpty())
    }

    /** Entities defined inside the file (the "billion laughs") are never expanded. */
    @Test fun ignoresDefinedEntities() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE FictionBook [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;"><!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">]>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><body><section><p>Безпечно</p></section></body></FictionBook>"""
        val book = Fb2Reader.read(ByteArrayInputStream(bomb.toByteArray()))
        assertEquals("Безпечно", (book.blocks.single() as BookBlock.Text).text)
    }
}
