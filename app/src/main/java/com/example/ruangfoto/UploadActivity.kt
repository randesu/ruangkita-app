package com.ruangfoto.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ruangfoto.app.databinding.ActivityUploadBinding
import java.io.File
import java.util.concurrent.Executors

class UploadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUploadBinding
    private val store by lazy { UploadStore.get(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var session: File? = null
    private var visible = false
    private var closed = false
    private lateinit var rows: ArrayAdapter<String>
    private var displayed = emptyList<String>()
    private var loadingRule = false
    private var messageExpiresAt = 0L
    private val modes = listOf("all", "jpeg", "raw")

    private val refresh = object : Runnable {
        override fun run() {
            if (!visible || closed) return
            worker.execute {
                val result = runCatching {
                    store.summary() to store.jobs().filter { it.state != "skipped" && it.state != "cancelled" }.map {
                        "${File(it.path).name}\n${it.state.replaceFirstChar(Char::uppercase)}${if (it.error.isBlank()) "" else "\n${it.error}"}"
                    }
                }
                handler.post {
                    if (visible && !closed) result.onSuccess { (summary, jobs) ->
                        binding.queueSummary.text = summary
                        if (SystemClock.elapsedRealtime() >= messageExpiresAt) binding.transferProgress.text = UploadService.progress
                        binding.transferToggleButton.setText(if (store.paused) R.string.transfer_resume else R.string.transfer_pause)
                        if (jobs != displayed) { displayed = jobs; rows.clear(); rows.addAll(jobs) }
                    }.onFailure { binding.transferProgress.text = "Transfer queue unavailable" }
                }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.transferWorkspace.arrangeWorkspace()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        session = intent.getStringExtra(EXTRA_SESSION_PATH)?.let(::File)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.serverSettingsRow.setOnClickListener { startActivity(Intent(this, ServerSettingsActivity::class.java)) }
        rows = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        binding.queueList.adapter = rows
        binding.queueList.emptyView = binding.emptyQueue
        binding.photoFilterSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("JPEG + RAW", "JPEG only", "RAW only"))
        binding.autoUploadCheck.setOnCheckedChangeListener { _, _ -> if (!loadingRule) saveSession(false) }
        binding.photoFilterSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val directory = session ?: return
                if (!loadingRule && store.rule(directory).mode != modes[position]) saveSession(false)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        binding.transferToggleButton.setOnClickListener {
            if (store.paused) { store.paused = false; startUploads() }
            else UploadService.pause(this)
        }
        binding.stopUploadsButton.setOnClickListener { stopUploads() }
        binding.toolbar.menu.add(0, 1, 0, R.string.transfer_retry)
        binding.toolbar.menu.add(0, 2, 1, R.string.transfer_send_existing)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { store.retryFailed(); store.paused = false; startUploads() }
                2 -> saveSession(true)
            }
            true
        }
    }

    private fun stopUploads() {
        UploadService.pause(this)
        store.cancelUnfinished()
        loadSession()
        showMessage("Uploads stopped. Local photos are kept.")
    }

    private fun showMessage(message: String) {
        messageExpiresAt = SystemClock.elapsedRealtime() + 10_000
        binding.transferProgress.text = message
    }

    private fun loadSession() {
        loadingRule = true
        binding.sessionName.text = session?.name ?: "No photo session selected"
        binding.autoUploadCheck.isEnabled = session != null
        binding.photoFilterSpinner.isEnabled = session != null
        session?.let { directory ->
            val rule = store.rule(directory)
            binding.autoUploadCheck.isChecked = rule.enabled
            binding.photoFilterSpinner.setSelection(modes.indexOf(rule.mode).coerceAtLeast(0))
        }
        loadingRule = false
    }

    private fun saveSession(includeExisting: Boolean) {
        val directory = session ?: return
        try {
            val enabled = binding.autoUploadCheck.isChecked || includeExisting
            store.configureSession(directory, enabled, modes[binding.photoFilterSpinner.selectedItemPosition], includeExisting)
            loadSession()
            if (enabled && !store.paused) startUploads()
        } catch (error: Exception) {
            loadSession()
            showMessage(error.message ?: "Could not save session settings")
        }
    }

    private fun startUploads() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 210)
        try { UploadService.startFromVisibleApp(this) }
        catch (_: Exception) { store.paused = true; showMessage("Reopen Transfers to resume uploads") }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        loadSession()
        val settings = runCatching { store.settings() }.getOrNull()
        binding.serverSummary.text = settings?.let { "${it.protocol.name}  ${it.host}:${it.port}\n${it.folder}" } ?: getString(R.string.transfer_no_server)
        worker.execute { runCatching {
            store.scan()
            if (!store.paused && store.hasPending()) handler.post { if (visible && !closed) startUploads() }
        } }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() { visible = false; handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { closed = true; worker.shutdownNow(); super.onDestroy() }

    companion object {
        const val EXTRA_SESSION_PATH = "session_path"
        fun intent(context: Context, session: File?) = Intent(context, UploadActivity::class.java)
            .putExtra(EXTRA_SESSION_PATH, session?.absolutePath)
    }
}
