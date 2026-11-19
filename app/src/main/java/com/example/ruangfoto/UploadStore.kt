package com.ruangfoto.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun photoKind(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "jpeg"
    "arw", "nef", "nrw", "cr2", "cr3", "dng", "raf", "orf", "rw2", "pef" -> "raw"
    else -> null
}
internal fun uploadMatches(name: String, mode: String) = photoKind(name)?.let { mode == "all" || it == mode } ?: false
internal fun remotePhotoName(id: String, name: String): String = id + "-" + remoteSuffix(name)
internal fun remoteFramedName(id: String, name: String): String = id + "-framed-" + remoteSuffix(name)
private fun remoteSuffix(name: String): String = name.takeLast(120).map {
    if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '_'
}.joinToString("")

internal data class UploadJob(val id: String, val path: String, val size: Long, val modified: Long,
                              val name: String, val attempts: Int, val finalizing: Boolean,
                              val destination: String, val state: String, val error: String,
                              val sessionFolder: String?)
internal data class UploadRule(val enabled: Boolean = false, val mode: String = "all")

/** Database handles are process-local; all state transitions are transactional and survive process death. */
internal class UploadStore(private val context: Context) : SQLiteOpenHelper(context, "uploads.db", null, 3) {
    private val prefs = context.getSharedPreferences("ftp_settings", Context.MODE_PRIVATE)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(path TEXT PRIMARY KEY, enabled INTEGER NOT NULL, mode TEXT NOT NULL)")
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY, destination TEXT NOT NULL, path TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, name TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL DEFAULT 0, finalizing INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', session_folder TEXT, UNIQUE(destination,path,size,modified))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion == 3) { "Unsupported upload database version" }
        // Keep existing jobs at their original path, including ones recovering a remote rename.
        if (oldVersion < 2) db.execSQL("ALTER TABLE jobs ADD COLUMN session_folder TEXT")
        // Preserve "only new" for sessions enabled before framed photos were eligible.
        db.rawQuery("SELECT path FROM sessions WHERE enabled=1", null).use { rows ->
            while (rows.moveToNext()) {
                val directory = File(rows.getString(0))
                val sessionFolder = directory.name
                forEachFramedJpeg(directory) { file -> insertFile(db, file, "skipped", sessionFolder, framed = true) }
            }
        }
    }

    fun settings(): TransferSettings? {
        val host = prefs.getString("host", null) ?: return null
        val encrypted = prefs.getString("secret", null) ?: error("Password unavailable; save transfer settings again")
        val password = try {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { error("Saved password cannot be unlocked; enter and save it again") }
        val protocol = prefs.getString("protocol", null)?.let { runCatching { TransferProtocol.valueOf(it) }.getOrNull() }
            ?: if (prefs.getBoolean("tls", true)) TransferProtocol.FTPS else TransferProtocol.FTP
        return TransferSettings(host, prefs.getInt("port", protocol.port), prefs.getString("user", "")!!,
            password, prefs.getString("folder", "/")!!, protocol, prefs.getString("host_key", "")!!, timing())
    }

    private fun timing() = TransferTiming(
        prefs.getInt("connect_timeout_seconds", 15),
        prefs.getInt("read_timeout_seconds", 30),
        prefs.getInt("retry_delay_seconds", 5),
        prefs.getInt("max_retry_delay_seconds", 300),
        prefs.getInt("max_attempts", 8))

    @Synchronized fun saveSettings(value: TransferSettings) {
        value.validate()
        val previousDestination = prefs.getString("destination", null)
        val changed = previousDestination != value.destination()
        require(!changed || countUnfinished() == 0) { "Finish or cancel pending uploads before changing the server" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(value.password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        if (changed) writableDatabase.execSQL("UPDATE sessions SET enabled=0")
        check(prefs.edit().putString("host", value.host).putInt("port", value.port)
            .putString("user", value.user).putString("folder", value.folder)
            .putBoolean("tls", value.protocol == TransferProtocol.FTPS).putString("protocol", value.protocol.name)
            .putString("host_key", value.hostKeyFingerprint)
            .putInt("connect_timeout_seconds", value.timing.connectTimeoutSeconds)
            .putInt("read_timeout_seconds", value.timing.readTimeoutSeconds)
            .putInt("retry_delay_seconds", value.timing.retryDelaySeconds)
            .putInt("max_retry_delay_seconds", value.timing.maxRetryDelaySeconds)
            .putInt("max_attempts", value.timing.maxAttempts)
            .putString("secret", encrypted).putString("destination", value.destination()).commit()) { "Cannot save transfer settings" }
    }

    private fun secretKey(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey("ruangfoto-ftp", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ruangfoto-ftp", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) { check(prefs.edit().putBoolean("paused", value).commit()) }

    fun rule(directory: File): UploadRule = readableDatabase.rawQuery("SELECT enabled,mode FROM sessions WHERE path=?", arrayOf(directory.absolutePath)).use {
        if (it.moveToFirst()) UploadRule(it.getInt(0) != 0, it.getString(1)) else UploadRule()
    }

    @Synchronized fun configureSession(directory: File, enabled: Boolean, mode: String, includeExisting: Boolean) {
        require(mode in setOf("all", "raw", "jpeg"))
        if (enabled) require(settings() != null) { "Configure the FTP server first" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val sessionFolder = directory.name
            fun baseline(file: File, isFramed: Boolean) {
                val matched = includeExisting && uploadMatches(file.name, mode)
                insertFile(db, file, if (matched) "pending" else "skipped", sessionFolder, isFramed)
                if (matched) db.execSQL(
                    "UPDATE jobs SET state='pending',error='',due=0,session_folder=CASE WHEN state='skipped' THEN ? ELSE session_folder END WHERE destination=? AND path=? AND size=? AND modified=? AND state IN ('skipped','cancelled')",
                    arrayOf(sessionFolder, prefs.getString("destination", "")!!, file.absolutePath, file.length(), file.lastModified()))
            }
            // Baseline every existing photo when opting in; changing a filter is not retroactive.
            for (file in directory.listFiles().orEmpty()) {
                if (file.isFile && photoKind(file.name) != null) baseline(file, false)
            }
            forEachFramedJpeg(directory) { baseline(it, true) }
            db.insertWithOnConflict("sessions", null, ContentValues().apply {
                put("path", directory.absolutePath); put("enabled", if (enabled) 1 else 0); put("mode", mode)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun scan() {
        val db = writableDatabase
        db.rawQuery("SELECT path,mode FROM sessions WHERE enabled=1", null).use { rows ->
            while (rows.moveToNext()) {
                val mode = rows.getString(1)
                val directory = File(rows.getString(0))
                val sessionFolder = directory.name
                for (file in directory.listFiles().orEmpty()) {
                    if (file.isFile && file.length() > 0 && photoKind(file.name) != null) {
                        insertFile(db, file, if (uploadMatches(file.name, mode)) "pending" else "skipped", sessionFolder)
                    }
                }
                forEachFramedJpeg(directory) { file ->
                    insertFile(db, file, if (mode == "raw") "skipped" else "pending", sessionFolder, framed = true)
                }
            }
        }
    }

    private inline fun forEachFramedJpeg(directory: File, action: (File) -> Unit) {
        for (file in framedPhotoDirectory(directory).listFiles().orEmpty()) {
            if (file.isFile && file.length() > 0 && photoKind(file.name) == "jpeg") action(file)
        }
    }

    private fun insertFile(db: SQLiteDatabase, file: File, state: String, sessionFolder: String, framed: Boolean = false) {
        val destination = prefs.getString("destination", "")!!
        db.rawQuery("SELECT 1 FROM jobs WHERE destination=? AND path=? AND size=? AND modified=? LIMIT 1",
            arrayOf(destination, file.absolutePath, file.length().toString(), file.lastModified().toString())).use {
            if (it.moveToFirst()) return
        }
        val id = UUID.randomUUID().toString()
        db.insertWithOnConflict("jobs", null, ContentValues().apply {
            put("id", id); put("path", file.absolutePath); put("size", file.length()); put("modified", file.lastModified())
            put("destination", destination)
            put("session_folder", sessionFolder)
            put("name", if (framed) remoteFramedName(id, file.name) else remotePhotoName(id, file.name)); put("state", state)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun next(): UploadJob? = readableDatabase.rawQuery(
        "SELECT id,path,size,modified,name,attempts,finalizing,destination,state,error,session_folder FROM jobs WHERE destination=? AND state='pending' AND due<=? ORDER BY rowid LIMIT 1",
        arrayOf(prefs.getString("destination", "")!!, System.currentTimeMillis().toString())).use {
        if (!it.moveToFirst()) null else uploadJob(it)
    }

    fun jobs(limit: Int = 50): List<UploadJob> = readableDatabase.rawQuery(
        "SELECT id,path,size,modified,name,attempts,finalizing,destination,state,error,session_folder FROM jobs ORDER BY rowid DESC LIMIT ?",
        arrayOf(limit.toString())).use { cursor -> buildList { while (cursor.moveToNext()) add(uploadJob(cursor)) } }

    private fun uploadJob(cursor: android.database.Cursor) = UploadJob(
        cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3), cursor.getString(4),
        cursor.getInt(5), cursor.getInt(6) != 0, cursor.getString(7), cursor.getString(8), cursor.getString(9),
        cursor.getString(10))
    fun finalizing(job: UploadJob) { writableDatabase.execSQL("UPDATE jobs SET finalizing=1 WHERE id=? AND state='pending'", arrayOf(job.id)) }
    fun done(job: UploadJob) { writableDatabase.execSQL("UPDATE jobs SET state='done',error='' WHERE id=? AND state='pending'", arrayOf(job.id)) }
    fun fail(job: UploadJob, message: String, permanent: Boolean) {
        val attempts = job.attempts + 1
        val policy = timing()
        val delay = policy.retryDelayMillis(attempts)
        writableDatabase.execSQL("UPDATE jobs SET state=?,attempts=?,due=?,error=? WHERE id=? AND state='pending'",
            arrayOf<Any>(if (permanent || attempts >= policy.maxAttempts) "failed" else "pending", attempts, System.currentTimeMillis() + delay, message, job.id))
    }
    fun retryFailed() { writableDatabase.execSQL("UPDATE jobs SET state='pending',attempts=0,due=0,error='' WHERE state='failed'") }
    @Synchronized fun cancelUnfinished() {
        writableDatabase.execSQL("UPDATE jobs SET state='cancelled',error='' WHERE state IN ('pending','failed')")
        writableDatabase.execSQL("UPDATE sessions SET enabled=0")
    }
    fun countUnfinished(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM jobs WHERE state IN ('pending','failed')", null).use { it.moveToFirst(); it.getInt(0) }
    fun hasPending(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM jobs WHERE state='pending' LIMIT 1", null).use { it.moveToFirst() }
    fun summary(): String {
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM jobs GROUP BY state", null).use {
            while (it.moveToNext()) counts[it.getString(0)] = it.getInt(1)
        }
        val error = readableDatabase.rawQuery("SELECT error FROM jobs WHERE error<>'' ORDER BY rowid DESC LIMIT 1", null).use {
            if (it.moveToFirst()) "\n${it.getString(0)}" else ""
        }
        return "${if (paused) "Paused · " else ""}${counts["pending"] ?: 0} waiting · ${counts["done"] ?: 0} uploaded · ${counts["failed"] ?: 0} failed$error"
    }

    companion object {
        @Volatile private var instance: UploadStore? = null
        fun get(context: Context): UploadStore = instance ?: synchronized(this) {
            instance ?: UploadStore(context.applicationContext).also { instance = it }
        }
    }
}