package com.ruangfoto.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class UploadStoreTest {
    @Test fun keepsBaselineDedupAndDestinationOwnershipAcrossReopen() {
        val prefix = "upload-test-${UUID.randomUUID()}-"
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedContext(base, prefix)
        val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        var store = UploadStore(context)
        val settings = TransferSettings("first.example", 21, "user", "secret", "/photos", TransferProtocol.FTPS)
        try {
            store.saveSettings(settings)
            File(directory, "old.JPG").writeBytes(byteArrayOf(1))
            store.configureSession(directory, true, "raw", false)
            File(directory, "new.ARW").writeBytes(byteArrayOf(0, 10, 13, -1))
            File(directory, "new.JPG").writeBytes(byteArrayOf(2))
            File(directory, "unfinished.ARW.part").writeBytes(byteArrayOf(3))
            store.scan(); store.scan()
            assertEquals(1, store.countUnfinished())
            val original = store.next()!!
            store.finalizing(original)
            store.close()
            store = UploadStore(context)
            val recovered = store.next()!!
            assertEquals(original.id, recovered.id)
            assertEquals(original.name, recovered.name)
            assertEquals(directory.name, recovered.sessionFolder)
            assertTrue(recovered.finalizing)
            assertEquals("secret", store.settings()!!.password)
            try { store.saveSettings(settings.copy(host = "second.example")); fail("Queued upload silently redirected") }
            catch (_: IllegalArgumentException) { }
            store.fail(recovered, "Bad destination", true)
            store.cancelUnfinished()
            store.saveSettings(settings.copy(host = "second.example"))
            assertFalse(store.rule(directory).enabled)
            store.configureSession(directory, true, "raw", true)
            store.scan()
            assertEquals(1, store.countUnfinished())
            assertNotEquals(original.id, store.next()!!.id)
        } finally {
            store.close()
            base.deleteDatabase(prefix + "uploads.db")
            base.getSharedPreferences(prefix + "ftp_settings", Context.MODE_PRIVATE).edit().clear().commit()
            directory.deleteRecursively()
        }
    }

    @Test fun uploadsFramedJpegsByFilterAndBaselineWithoutDuplicates() {
        val prefix = "framed-test-${UUID.randomUUID()}-"
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedContext(base, prefix)
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        var store = UploadStore(context)
        try {
            store.saveSettings(TransferSettings("framed.example", 21, "user", "secret", "/photos", TransferProtocol.FTPS))

            // JPEG mode queues finalized framed JPEGs only: no empty, partial, or nested files.
            val jpegSession = File(root, "jpeg-session").apply { mkdirs() }
            File(jpegSession, "shot.JPG").writeBytes(byteArrayOf(1))
            File(jpegSession, "shot.ARW").writeBytes(byteArrayOf(2))
            val jpegFramed = File(jpegSession, "Framed").apply { mkdirs() }
            File(jpegFramed, "shot.JPG").writeBytes(byteArrayOf(3))
            File(jpegFramed, "zero.JPG").writeBytes(byteArrayOf())
            File(jpegFramed, "nested").mkdirs()
            File(File(jpegFramed, "nested"), "deep.JPG").writeBytes(byteArrayOf(4))
            File(jpegFramed, ".ruangfoto-partial.part").writeBytes(byteArrayOf(5))
            store.configureSession(jpegSession, true, "jpeg", true)
            store.scan(); store.scan()
            assertEquals(2, store.countUnfinished())
            val pending = store.jobs().filter { it.state == "pending" }
            assertEquals(2, pending.size)
            assertTrue(store.jobs().none { it.path.endsWith("zero.JPG") || it.path.endsWith("deep.JPG") || it.path.endsWith(".part") })
            val framedJob = pending.single { it.path.startsWith(jpegFramed.absolutePath) }
            assertEquals("shot.JPG", File(framedJob.path).name)
            assertTrue(framedJob.name.endsWith("-framed-shot.JPG"))
            assertTrue(pending.any { it.name.endsWith("-shot.JPG") && it.name != framedJob.name })
            assertEquals(jpegSession.name, framedJob.sessionFolder)
            assertEquals(store.settings()!!.destination(), framedJob.destination)

            // RAW-only keeps originals RAW and never queues framed JPEGs.
            val rawSession = File(root, "raw-session").apply { mkdirs() }
            File(rawSession, "raw.JPG").writeBytes(byteArrayOf(6))
            File(rawSession, "raw.ARW").writeBytes(byteArrayOf(7))
            val rawFramed = File(rawSession, "Framed").apply { mkdirs() }
            File(rawFramed, "raw.JPG").writeBytes(byteArrayOf(8))
            store.configureSession(rawSession, true, "raw", true)
            store.scan()
            assertEquals(3, store.countUnfinished())
            assertEquals("skipped", store.jobs().single { it.path == File(rawFramed, "raw.JPG").absolutePath }.state)
            File(rawSession, "later.ARW").writeBytes(byteArrayOf(9))
            File(rawFramed, "later.JPG").writeBytes(byteArrayOf(10))
            store.scan()
            assertEquals(4, store.countUnfinished())
            assertEquals("skipped", store.jobs().single { it.path == File(rawFramed, "later.JPG").absolutePath }.state)

            // 'Only new' activation baselines existing framed photos, then uploads later renders.
            val newSession = File(root, "new-session").apply { mkdirs() }
            val newFramed = File(newSession, "Framed").apply { mkdirs() }
            File(newFramed, "old.JPG").writeBytes(byteArrayOf(11))
            store.configureSession(newSession, true, "jpeg", false)
            store.scan()
            assertEquals(4, store.countUnfinished())
            assertEquals("skipped", store.jobs().single { it.path == File(newFramed, "old.JPG").absolutePath }.state)
            File(newFramed, "fresh.JPG").writeBytes(byteArrayOf(12))
            store.scan()
            assertEquals(5, store.countUnfinished())
            assertTrue(store.jobs().single { it.path == File(newFramed, "fresh.JPG").absolutePath }.name.endsWith("-framed-fresh.JPG"))

            // A session enabled before this schema change never bulk-resends its framed backlog.
            val upgraded = File(root, "upgraded").apply { mkdirs() }
            store.configureSession(upgraded, true, "jpeg", false)
            val upgradedFramed = File(upgraded, "Framed").apply { mkdirs() }
            File(upgradedFramed, "historical.JPG").writeBytes(byteArrayOf(13))
            store.writableDatabase.version = 2
            store.close()
            store = UploadStore(context)
            store.scan()
            assertEquals(5, store.countUnfinished())
            assertEquals("skipped", store.jobs().single { it.path == File(upgradedFramed, "historical.JPG").absolutePath }.state)
            File(upgradedFramed, "after-upgrade.JPG").writeBytes(byteArrayOf(14))
            store.scan()
            assertEquals(6, store.countUnfinished())
        } finally {
            store.close()
            base.deleteDatabase(prefix + "uploads.db")
            base.getSharedPreferences(prefix + "ftp_settings", Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }

    @Test fun framedOnlyQueuesOnlyRenderedJpegs() {
        val prefix = "framed-only-${UUID.randomUUID()}-"
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedContext(base, prefix)
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        var store = UploadStore(context)
        try {
            store.saveSettings(TransferSettings("framed.example", 21, "user", "secret", "/photos", TransferProtocol.FTP))
            val session = File(root, "wedding").apply { mkdirs() }
            File(session, "original.JPG").writeBytes(byteArrayOf(1))
            File(session, "original.ARW").writeBytes(byteArrayOf(2))
            val framed = File(session, "Framed").apply { mkdirs() }
            File(framed, "original.JPG").writeBytes(byteArrayOf(3))

            store.configureSession(session, true, "framed", true)
            store.scan()
            var pending = store.jobs().filter { it.state == "pending" }
            assertEquals(1, pending.size)
            assertEquals(File(framed, "original.JPG").absolutePath, pending.single().path)
            assertTrue(pending.single().name.endsWith("-framed-original.JPG"))

            File(session, "later.JPG").writeBytes(byteArrayOf(4))
            File(session, "later.ARW").writeBytes(byteArrayOf(5))
            File(framed, "later.JPG").writeBytes(byteArrayOf(6))
            store.scan()
            pending = store.jobs().filter { it.state == "pending" }
            assertEquals(2, pending.size)
            assertTrue(pending.all { it.path.startsWith(framed.absolutePath) })
            assertTrue(store.jobs().filter { it.path.startsWith(session.absolutePath) && !it.path.startsWith(framed.absolutePath) }
                .all { it.state == "skipped" })

            val onlyNew = File(root, "only-new").apply { mkdirs() }
            val onlyNewFramed = File(onlyNew, "Framed").apply { mkdirs() }
            File(onlyNew, "old.JPG").writeBytes(byteArrayOf(7))
            File(onlyNewFramed, "old.JPG").writeBytes(byteArrayOf(8))
            store.configureSession(onlyNew, true, "framed", false)
            store.scan()
            assertEquals("skipped", store.jobs().single { it.path == File(onlyNewFramed, "old.JPG").absolutePath }.state)
            File(onlyNewFramed, "new.JPG").writeBytes(byteArrayOf(9))
            store.scan()
            assertEquals("pending", store.jobs().single { it.path == File(onlyNewFramed, "new.JPG").absolutePath }.state)
            assertEquals("skipped", store.jobs().single { it.path == File(onlyNew, "old.JPG").absolutePath }.state)
        } finally {
            store.close()
            base.deleteDatabase(prefix + "uploads.db")
            base.getSharedPreferences(prefix + "ftp_settings", Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }

}

private class IsolatedContext(base: Context, private val prefix: String) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix + name, mode)
    override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix + name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = baseContext.openOrCreateDatabase(prefix + name, mode, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) = baseContext.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
}