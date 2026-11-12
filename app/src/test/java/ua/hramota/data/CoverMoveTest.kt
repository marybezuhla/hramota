package ua.hramota.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A cover chosen for a document stays with it when its content changes (an edit saved, notes written into a PDF). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CoverMoveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theCoverGoesToTheNewFingerprint() {
        val before = "a".repeat(32); val after = "b".repeat(32)
        val png = byteArrayOf(1, 2, 3, 4)
        assertTrue(Shelf.putCover(context, before, png))
        Shelf.moveCover(context, before, after)
        assertFalse(Shelf.hasCover(context, before))
        assertArrayEquals(png, Shelf.loadCoverBytes(context, after))
    }

    @Test fun nothingToMove() {
        val before = "c".repeat(32); val after = "d".repeat(32)
        Shelf.moveCover(context, before, after)
        assertFalse(Shelf.hasCover(context, after))
    }
}
