package com.ruangfoto.app

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ruangfoto.app.databinding.ActivityServerSettingsBinding
import java.util.concurrent.Executors

class ServerSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityServerSettingsBinding
    private val store by lazy { UploadStore.get(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var activeCheck: TransferClient? = null
    private var currentSettings: TransferSettings? = null
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.serverWorkspace.arrangeWorkspace(scrollTogether = true)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.protocolSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, TransferProtocol.entries.map { it.title })
        loadSavedSettings()
        var previousProtocol = binding.protocolSpinner.selectedItemPosition
        binding.protocolSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val protocol = TransferProtocol.entries[position]
                if (position != previousProtocol) binding.portInput.setText(protocol.port.toString())
                previousProtocol = position
                updateProtocolFields(protocol)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        binding.saveServerButton.setOnClickListener { saveServer() }
        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.trustHostButton.setOnClickListener { readSshFingerprint() }
        binding.browseFolderButton.setOnClickListener { browseRemoteFolders() }
        binding.timingSettingsButton.setOnClickListener {
            val expanded = binding.timingOptions.visibility != View.VISIBLE
            binding.timingOptions.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.timingSettingsButton.setText(if (expanded) R.string.transfer_hide_timing else R.string.transfer_timing)
        }
    }

    private fun loadSavedSettings() {
        val saved = runCatching { store.settings() }
        currentSettings = saved.getOrNull()
        val value = currentSettings
        if (value != null) {
            binding.protocolSpinner.setSelection(value.protocol.ordinal)
            binding.hostInput.setText(value.host)
            binding.portInput.setText(value.port.toString())
            binding.userInput.setText(value.user)
            binding.passwordInput.setText(value.password)
            binding.folderInput.setText(if (value.folder == "." &&
                (value.protocol == TransferProtocol.FTP || value.protocol == TransferProtocol.FTPS)) "" else value.folder)
            binding.hostFingerprintInput.setText(value.hostKeyFingerprint)
        } else {
            binding.protocolSpinner.setSelection(TransferProtocol.FTPS.ordinal)
            binding.portInput.setText(TransferProtocol.FTPS.port.toString())
        }
        val timing = value?.timing ?: TransferTiming()
        binding.connectTimeoutInput.setText(timing.connectTimeoutSeconds.toString())
        binding.readTimeoutInput.setText(timing.readTimeoutSeconds.toString())
        binding.retryDelayInput.setText(timing.retryDelaySeconds.toString())
        binding.maxRetryDelayInput.setText(timing.maxRetryDelaySeconds.toString())
        binding.maxAttemptsInput.setText(timing.maxAttempts.toString())
        saved.exceptionOrNull()?.let { updateMessage("Saved password unavailable; enter it again") }
        updateProtocolFields(value?.protocol ?: TransferProtocol.FTPS)
    }

    private fun updateProtocolFields(protocol: TransferProtocol) {
        val ssh = protocol == TransferProtocol.SFTP || protocol == TransferProtocol.SCP
        binding.hostFingerprintLayout.visibility = if (ssh) View.VISIBLE else View.GONE
        binding.trustHostButton.visibility = if (ssh) View.VISIBLE else View.GONE
        binding.folderLayout.setHint(if (ssh) R.string.transfer_folder else R.string.transfer_ftp_base_folder)
        binding.folderLayout.helperText = if (ssh) null else getString(R.string.transfer_ftp_folder_hint)
        binding.protocolNote.text = when (protocol) {
            TransferProtocol.FTP -> "FTP is unencrypted; credentials and photos can be read on the network."
            TransferProtocol.FTPS -> "FTPS encrypts the connection and verifies the server certificate."
            TransferProtocol.SFTP -> "SFTP uses SSH with a pinned server fingerprint."
            TransferProtocol.SCP -> "SCP uses SSH, a pinned fingerprint, and remote shell rename permission."
        }
    }

    private fun values(allowUnpinnedHost: Boolean = false): TransferSettings {
        val protocol = TransferProtocol.values()[binding.protocolSpinner.selectedItemPosition]
        val folder = binding.folderInput.text.toString().trim()
        val defaultFolder = if (protocol == TransferProtocol.FTP || protocol == TransferProtocol.FTPS) "." else "/"
        return TransferSettings(
            binding.hostInput.text.toString().trim(),
            binding.portInput.text.toString().toIntOrNull() ?: 0,
            binding.userInput.text.toString().trim(),
            binding.passwordInput.text.toString(),
            folder.ifBlank { defaultFolder },
            protocol,
            binding.hostFingerprintInput.text.toString().trim(),
            TransferTiming(
                binding.connectTimeoutInput.text.toString().toIntOrNull() ?: 0,
                binding.readTimeoutInput.text.toString().toIntOrNull() ?: 0,
                binding.retryDelayInput.text.toString().toIntOrNull() ?: 0,
                binding.maxRetryDelayInput.text.toString().toIntOrNull() ?: 0,
                binding.maxAttemptsInput.text.toString().toIntOrNull() ?: 0)
        ).also { it.validate(allowUnpinnedHost) }
    }

    private fun saveServer() {
        try {
            store.saveSettings(values())
            currentSettings = store.settings()
            updateMessage("Server settings saved")
        } catch (error: Exception) {
            updateMessage(error.message ?: "Could not save server settings")
        }
    }

    private fun testConnection() {
        val settings = try { values() } catch (error: Exception) {
            updateMessage(error.message ?: "Check server settings")
            return
        }
        val client = transferClient(settings)
        activeCheck = client
        binding.testConnectionButton.isEnabled = false
        updateMessage("Testing connection, write, rename, and cleanup…")
        worker.execute {
            val message = try {
                client.test(settings)
                "Connection and remote write test succeeded"
            } catch (error: Exception) {
                when (error) {
                    is javax.net.ssl.SSLException -> "FTPS certificate verification failed; check the server certificate"
                    is TransferFailure -> error.message ?: "Transfer test failed"
                    else -> "Connection failed; check the host, credentials, remote folder, and permissions"
                }
            }
            handler.post {
                if (closed) return@post
                activeCheck = null
                if (!closed) {
                    binding.testConnectionButton.isEnabled = true
                    updateMessage(message)
                }
            }
        }
    }

    private fun readSshFingerprint() {
        val settings = try { values(allowUnpinnedHost = true) } catch (error: Exception) {
            updateMessage(error.message ?: "Check SSH server settings")
            return
        }
        if (settings.protocol != TransferProtocol.SFTP && settings.protocol != TransferProtocol.SCP) return
        binding.trustHostButton.isEnabled = false
        updateMessage("Reading SSH host key; password is not sent during fingerprint discovery…")
        worker.execute {
            val result = runCatching { SshTransfer.discoverFingerprint(settings) }
            handler.post {
                if (closed) return@post
                binding.trustHostButton.isEnabled = true
                result.onSuccess { fingerprint ->
                    AlertDialog.Builder(this)
                        .setTitle("Verify SSH server fingerprint")
                        .setMessage("$fingerprint\n\nCompare this fingerprint with the server administrator before trusting it. Credentials are not sent during this check.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Trust and save") { _, _ ->
                            binding.hostFingerprintInput.setText(fingerprint)
                            updateMessage("Fingerprint pinned. Save server settings next.")
                        }.show()
                }.onFailure { updateMessage("Could not read SSH fingerprint; check the host, port, and network") }
            }
        }
    }

    private fun browseRemoteFolders() {
        val settings = try { values() } catch (error: Exception) {
            updateMessage(error.message ?: "Check server settings")
            return
        }
        browseRemoteFolders(settings, settings.folder.ifBlank { "/" })
    }

    private fun browseRemoteFolders(settings: TransferSettings, path: String) {
        binding.browseFolderButton.isEnabled = false
        updateMessage("Reading folders from $path…")
        val client = transferClient(settings)
        activeCheck = client
        worker.execute {
            val result = runCatching { client.listDirectories(settings, path) }
            handler.post {
                if (closed) return@post
                activeCheck = null
                binding.browseFolderButton.isEnabled = true
                result.onSuccess { folders ->
                    val entries = (if (path == "/") emptyList() else listOf("../")) + folders.map { "$it/" }
                    AlertDialog.Builder(this)
                        .setTitle("Remote folders · $path")
                        .setItems(entries.toTypedArray()) { _, index ->
                            val selected = entries[index]
                            val next = if (selected == "../") parentPath(path) else joinPath(path, selected.removeSuffix("/"))
                            browseRemoteFolders(settings, next)
                        }
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Use this folder") { _, _ -> binding.folderInput.setText(path) }
                        .show()
                    updateMessage("Choose a folder, or use the current location")
                }.onFailure { error ->
                    updateMessage(error.message ?: "Cannot list this folder; enter the remote path manually")
                }
            }
        }
    }

    private fun parentPath(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }
    private fun joinPath(parent: String, child: String) = if (parent == "/") "/$child" else "${parent.trimEnd('/')}/$child"

    private fun updateMessage(message: String) {
        binding.serverStatus.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        binding.serverStatus.text = message
    }

    override fun onDestroy() {
        closed = true
        activeCheck?.cancel()
        worker.shutdownNow()
        binding.passwordInput.text?.clear()
        super.onDestroy()
    }
}
