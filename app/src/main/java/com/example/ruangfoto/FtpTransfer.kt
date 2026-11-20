package com.ruangfoto.app

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.UUID
import javax.net.ssl.SSLContext

internal enum class TransferProtocol(val title: String, val port: Int) {
    FTP("FTP (unencrypted)", 21),
    FTPS("FTPS (explicit TLS)", 21),
    SFTP("SFTP over SSH", 22),
    SCP("SCP over SSH", 22)
}

internal data class TransferTiming(
    val connectTimeoutSeconds: Int = 15,
    val readTimeoutSeconds: Int = 30,
    val retryDelaySeconds: Int = 5,
    val maxRetryDelaySeconds: Int = 300,
    val maxAttempts: Int = 8
) {
    fun validate() {
        require(connectTimeoutSeconds in 1..300) { "Connection timeout must be 1–300 seconds" }
        require(readTimeoutSeconds in 1..3600) { "Transfer timeout must be 1–3600 seconds" }
        require(retryDelaySeconds in 1..3600) { "Retry delay must be 1–3600 seconds" }
        require(maxRetryDelaySeconds in retryDelaySeconds..86400) { "Maximum retry delay must be at least the initial delay and at most 86400 seconds" }
        require(maxAttempts in 1..50) { "Maximum attempts must be 1–50" }
    }

    fun retryDelayMillis(attempt: Int): Long = minOf(
        maxRetryDelaySeconds * 1000L,
        retryDelaySeconds * 1000L * (1L shl (attempt - 1).coerceIn(0, 30)))
}

internal data class TransferSettings(
    val host: String, val port: Int, val user: String, val password: String,
    val folder: String, val protocol: TransferProtocol, val hostKeyFingerprint: String = "",
    val timing: TransferTiming = TransferTiming()
) {
    fun validate(allowUnpinnedHost: Boolean = false) {
        timing.validate()
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/\\\r\n\u0000" }) { "Enter a hostname or IP, without a URL or path" }
        require(port in 1..65535) { "Port must be 1–65535" }
        require(user.isNotBlank()) { "Username is required" }
        require(listOf(user, password, folder, hostKeyFingerprint).none { s -> s.any { it == '\r' || it == '\n' || it == '\u0000' } }) { "Control characters are not allowed" }
        require(folder.isNotBlank() && '\\' !in folder && folder.split('/').none { it == ".." }) { "Enter a remote folder without '..'" }
        if (protocol == TransferProtocol.SFTP || protocol == TransferProtocol.SCP) {
            require(allowUnpinnedHost || hostKeyFingerprint.startsWith("SHA256:")) { "Read and trust the SSH server fingerprint before saving" }
            require(hostKeyFingerprint.isBlank() || hostKeyFingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?"))) { "SSH fingerprint must use SHA256 format" }
        }
    }

    fun destination(): String = if (protocol == TransferProtocol.FTP || protocol == TransferProtocol.FTPS) {
        listOf(host.lowercase(), port.toString(), user, folder, (protocol == TransferProtocol.FTPS).toString()).joinToString("\n")
    } else {
        listOf(host.lowercase(), port.toString(), user, folder, protocol.name, hostKeyFingerprint).joinToString("\n")
    }
}


internal interface TransferClient {
    fun cancel()
    fun listDirectories(settings: TransferSettings, folder: String): List<String>
    fun test(settings: TransferSettings)
    fun upload(settings: TransferSettings, file: File, expectedSize: Long, expectedModified: Long,
               remoteName: String, wasFinalizing: Boolean, beforeRename: () -> Unit, progress: (Long) -> Unit)
}

internal fun transferClient(settings: TransferSettings, sessionFolder: String? = null): TransferClient = when (settings.protocol) {
    TransferProtocol.FTP, TransferProtocol.FTPS -> FtpTransfer(sessionFolder)
    TransferProtocol.SFTP, TransferProtocol.SCP -> SshTransfer()
}

internal class TransferFailure(message: String, val permanent: Boolean = false, val configuration: Boolean = false) : IOException(message)

/** One client per transfer. Cancellation closes sockets, including a blocked data transfer. */
internal class FtpTransfer(private val sessionFolder: String? = null) : TransferClient {
    @Volatile private var client: FTPClient? = null
    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
        try { client?.disconnect() } catch (_: IOException) { }
    }

    private fun connect(settings: TransferSettings): FTPClient {
        require(settings.protocol == TransferProtocol.FTP || settings.protocol == TransferProtocol.FTPS)
        settings.validate()
        checkCancelled()
        val ftp = if (settings.protocol == TransferProtocol.FTPS) {
            // Commons Net's default trust manager checks expiry only: use the platform CA store.
            FTPSClient(false, SSLContext.getInstance("TLS").apply { init(null, null, null) }).apply {
                isEndpointCheckingEnabled = true
            }
        } else FTPClient()
        client = ftp
        ftp.connectTimeout = settings.timing.connectTimeoutSeconds * 1000
        ftp.defaultTimeout = settings.timing.readTimeoutSeconds * 1000
        ftp.setDataTimeout(Duration.ofSeconds(settings.timing.readTimeoutSeconds.toLong()))
        ftp.controlEncoding = "UTF-8"
        checkCancelled()
        ftp.connect(settings.host, settings.port)
        checkCancelled()
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) fail(ftp, "Server refused the connection")
        if (!ftp.login(settings.user, settings.password)) throw TransferFailure("Login rejected; check username and password", true, true)
        if (ftp is FTPSClient) { ftp.execPBSZ(0); ftp.execPROT("P") }
        ftp.enterLocalPassiveMode()
        if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) fail(ftp, "Server rejected binary transfer")
        if (settings.folder != "." && !ftp.changeWorkingDirectory(settings.folder)) {
            throw TransferFailure("Remote folder unavailable; check path and permissions", true, true)
        }
        return ftp
    }

    private fun enterSessionFolder(ftp: FTPClient, folder: String) {
        val error = "Cannot open or create the FTP session folder; check permissions"
        if (ftp.changeWorkingDirectory(folder)) return
        if (ftp.replyCode !in 500..599) fail(ftp, error)
        if (!ftp.makeDirectory(folder) && ftp.replyCode !in 500..599) fail(ftp, error)
        // Another upload may have created the directory after our first CWD.
        if (!ftp.changeWorkingDirectory(folder)) fail(ftp, error)
    }

    /** Create the remote session directory before any photos arrive. */
    fun createSessionFolder(settings: TransferSettings, folder: String) {
        require(folder.isNotBlank() && folder !in setOf(".", "..") &&
                folder.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "Session name cannot be used as an FTP folder"
        }
        try {
            val ftp = connect(settings)
            enterSessionFolder(ftp, folder)
        } finally {
            close()
        }
    }

    override fun listDirectories(settings: TransferSettings, folder: String): List<String> {
        val ftp = connect(settings.copy(folder = folder))
        return try {
            ftp.listDirectories().map { it.name }.filter { it.isNotBlank() && it !in setOf(".", "..") && '/' !in it && '\\' !in it }
        } finally { close() }
    }
    override fun test(settings: TransferSettings) {
        val name = ".ruangfoto-test-${UUID.randomUUID()}"
        try {
            val ftp = connect(settings)
            if (!ftp.storeFile("$name.part", byteArrayOf(0, 10, 13, -1).inputStream())) fail(ftp, "Remote folder is not writable")
            if (!ftp.rename("$name.part", name)) fail(ftp, "Server cannot finalize uploads (rename required)")
            if (!ftp.deleteFile(name)) fail(ftp, "Server cannot delete the connection-test file")
        } finally {
            try { client?.deleteFile("$name.part") } catch (_: IOException) { }
            try { client?.deleteFile(name) } catch (_: IOException) { }
            close()
        }
    }

    override fun upload(settings: TransferSettings, file: File, expectedSize: Long, expectedModified: Long,
               remoteName: String, wasFinalizing: Boolean, beforeRename: () -> Unit,
               progress: (Long) -> Unit) {
        require(remoteName.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid remote filename" }
        if (sessionFolder != null && (sessionFolder.isBlank() || sessionFolder == "." || sessionFolder == ".." ||
                sessionFolder.any { it == '/' || it == '\\' || it.isISOControl() })) {
            throw TransferFailure("Session name cannot be used as an FTP folder", true)
        }
        if (!file.isFile || file.length() != expectedSize || file.lastModified() != expectedModified) {
            throw TransferFailure("Local photo changed or disappeared; original upload was not sent", true)
        }
        try {
            val ftp = connect(settings)
            if (sessionFolder != null) enterSessionFolder(ftp, sessionFolder)
            // The UUID name belongs to this durable job. Only a recorded rename attempt may recover it.
            val existing = ftp.mlistFile(remoteName) ?: ftp.listFiles(remoteName).firstOrNull { it.name == remoteName }
            if (existing != null) {
                if (wasFinalizing && existing.isFile && existing.size == expectedSize) return
                throw TransferFailure("Remote filename conflict; refusing to overwrite an existing photo", true)
            }
            var sent = 0L
            val listener = object : org.apache.commons.net.io.CopyStreamAdapter() {
                override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) {
                    checkCancelled()
                    sent = totalBytesTransferred
                    progress(sent)
                }
            }
            ftp.copyStreamListener = listener
            val stored = file.inputStream().buffered().use { ftp.storeFile("$remoteName.part", it) }
            checkCancelled()
            if (!stored) fail(ftp, "Photo transfer rejected")
            if (sent != expectedSize || file.length() != expectedSize || file.lastModified() != expectedModified) {
                throw TransferFailure("Local photo changed during upload; temporary remote file was not finalized", true)
            }
            beforeRename() // Persist BEFORE RNFR/RNTO: recover a lost reply without creating a duplicate.
            if (!ftp.rename("$remoteName.part", remoteName)) fail(ftp, "Server could not finalize the photo")
        } finally { close() }
    }

    private fun fail(ftp: FTPClient, message: String): Nothing {
        // Never surface untrusted replies, which may echo USER/PASS or filesystem details.
        throw TransferFailure("$message (FTP ${ftp.replyCode})", ftp.replyCode in 500..599, ftp.replyCode in 500..599)
    }
    private fun checkCancelled() { if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Transfer paused") }
    private fun close() { try { client?.disconnect() } catch (_: IOException) { }; client = null }
}