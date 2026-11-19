
package com.ruangfoto.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ruangfoto.app.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager

    private var usbConnection: UsbDeviceConnection? = null
    private var receiverRegistered = false

    @Volatile
    private var activityDestroyed = false

    @Volatile
    private var activeUsbDeviceName: String? = null

    private val nativeExecutor = Executors.newSingleThreadExecutor()

    private val publishExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    private var publishTask: ScheduledFuture<*>? = null

    private val nativeRunning = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    private val usbPermissionAction =
        "com.ruangfoto.app.USB_PERMISSION"

    private data class CameraInfo(
        val name: String,
        val vendorId: Int,
        val productId: Int
    )

    // =====================================
    // STORAGE DIRECTORIES
    // =====================================

    private val pluginDir: File by lazy {
        File(filesDir, "plugins")
    }

    private val outputDir: File by lazy {
        val externalBase = getExternalFilesDir(null)

        if (externalBase != null) {
            File(externalBase, "RuangFoto")
        } else {
            File(filesDir, "RuangFoto")
        }
    }

    private val sessionsDir: File by lazy {
        File(outputDir, "Sessions")
    }

    @Volatile
    private var activeSessionDir: File? = null

    @Volatile
    private var activeSessionName: String? = null

    @Volatile
    private var previewSeconds = FrameDesignStore.DEFAULT_PREVIEW_SECONDS
    private val previewHandler = Handler(Looper.getMainLooper())
    private val hidePreview = Runnable(::clearJpegPreview)

    @Volatile
    private var activeCameraName: String? = null

    private val publishedFiles = HashSet<String>()

    private val seenJpegNames = HashSet<String>()

    private val publishLock = Any()

    // =====================================
    // USB BROADCAST RECEIVER
    // =====================================

    private val permissionReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

            when (intent.action) {

                usbPermissionAction -> {

                    val device = getUsbDevice(intent)

                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )

                    if (device == null) {
                        updateStatus("USB permission response invalid")
                        return
                    }

                    if (granted) {
                        updateStatus("USB permission granted")
                        openCameraDevice(device)
                    } else {
                        updateStatus("USB permission denied")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {

                    val device = getUsbDevice(intent)
                    val camera = device?.let { getPtpCamera(it) }

                    if (device != null && camera != null) {
                        updateStatus("${camera.name} detected")
                        handleCameraDevice(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {

                    val device = getUsbDevice(intent)

                    if (
                        device != null &&
                        activeUsbDeviceName == device.deviceName
                    ) {

                        updateStatus(
                            if (nativeRunning.get()) {
                                "${activeCameraName ?: "Camera"} disconnected - stopping session"
                            } else {
                                "${activeCameraName ?: "Camera"} disconnected"
                            }
                        )

                        activeUsbDeviceName = null
                        activeCameraName = null

                        runOnUiThread {
                            binding.stopButton.isEnabled = false
                            binding.stopButton.text = "Start Receiving"
                        }

                        requestSessionStop()
                    }
                }
            }
        }
    }

    // =====================================
    // ACTIVITY
    // =====================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.receiverLayout.arrangeWorkspace()

        val rootView = binding.root

        val initialLeft = rootView.paddingLeft
        val initialTop = rootView.paddingTop
        val initialRight = rootView.paddingRight
        val initialBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(rootView)

        usbManager =
            getSystemService(Context.USB_SERVICE) as UsbManager

        setupPlugins()
        registerUsbReceiver()
        setupButtons()
        setupSessionScreen()
        updateDashboard()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.receiverLayout.arrangeWorkspace()
    }

    override fun onResume() {
        super.onResume()
        activeSessionDir?.let(::updateSessionFrameControls)
    }

    // =====================================
    // SESSION LIST SCREEN
    // =====================================

    private fun setupSessionScreen() {

        binding.sessionSetupLayout.visibility = View.VISIBLE
        binding.receiverLayout.visibility = View.GONE

        binding.addSessionButton.setOnClickListener {
            showCreateSessionDialog()
        }

        binding.backToSessionsButton.setOnClickListener {

            if (nativeRunning.get()) {
                updateStatus("Stop the current camera session first")
                return@setOnClickListener
            }

            showSessionList()
        }

        refreshSessionList()
    }

    private fun showCreateSessionDialog() {

        val input = android.widget.EditText(this).apply {

            hint = "Example: Wedding - Andi & Sinta"

            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

            setSingleLine(true)
            setPadding(40, 24, 40, 24)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New Session")
            .setMessage("Enter a name for your new session.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CREATE", null)
            .create()

        dialog.setOnShowListener {

            dialog.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {

                val inputName = input.text.toString().trim()

                if (inputName.isBlank()) {
                    input.error = "Session name is required"
                    return@setOnClickListener
                }

                val safeName = inputName
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim()
                    .trim('.')

                if (safeName.isBlank()) {
                    input.error = "Invalid session name"
                    return@setOnClickListener
                }

                if (!sessionsDir.exists() && !sessionsDir.mkdirs()) {
                    input.error = "Cannot create sessions directory"
                    return@setOnClickListener
                }

                var uniqueName = safeName
                var suffix = 2

                while (File(sessionsDir, uniqueName).exists()) {
                    uniqueName = "$safeName ($suffix)"
                    suffix++
                }

                val newSessionDir = File(sessionsDir, uniqueName)

                if (!newSessionDir.mkdirs()) {
                    input.error = "Failed to create session folder"
                    return@setOnClickListener
                }

                dialog.dismiss()
                openSession(newSessionDir)
            }
        }

        dialog.show()
    }

    private fun refreshSessionList() {

        binding.sessionsListContainer.removeAllViews()

        if (!sessionsDir.exists()) {
            sessionsDir.mkdirs()
        }

        val sessions = sessionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (sessions.isEmpty()) {

            val emptyText = android.widget.TextView(this).apply {

                text = "No sessions yet.\nTap + to create your first session."
                textSize = 14f

                setTextColor(
                    android.graphics.Color.parseColor("#89968A")
                )

                gravity = android.view.Gravity.CENTER
                setPadding(24, 48, 24, 48)
            }

            binding.sessionsListContainer.addView(emptyText)
            return
        }

        for (session in sessions) {

            val files = session.listFiles()
                ?.filter { it.isFile && isSupportedPhoto(it) }
                ?: emptyList()

            val totalBytes = files.sumOf { it.length() }
            val jpegCount = files.count { isJpeg(it) }
            val rawCount = files.count { isRaw(it) }

            val sizeText = String.format(
                Locale.US,
                "%.1f MB",
                totalBytes / (1024.0 * 1024.0)
            )

            val card = android.widget.LinearLayout(this).apply {

                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(18, 16, 18, 16)

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(
                            android.graphics.Color.parseColor("#202A22")
                        )
                        cornerRadius = 18f
                    }

                isClickable = true
                isFocusable = true
            }

            val nameText = android.widget.TextView(this).apply {

                text = session.name
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)

                setTextColor(
                    android.graphics.Color.parseColor("#F2F4EC")
                )

                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }

            val detailText = android.widget.TextView(this).apply {

                text = "$jpegCount JPEG  ·  $rawCount RAW  ·  $sizeText"
                textSize = 12f

                setTextColor(
                    android.graphics.Color.parseColor("#9BA69B")
                )

                setPadding(0, 7, 0, 0)
            }

            card.addView(nameText)
            card.addView(detailText)

            card.setOnClickListener {
                openSession(session)
            }

            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10
            }

            binding.sessionsListContainer.addView(card, params)
        }
    }

    private fun showSessionList() {

        activeSessionDir = null
        activeSessionName = null
        activeCameraName = null
        clearJpegPreview()

        binding.receiverLayout.visibility = View.GONE
        binding.sessionSetupLayout.visibility = View.VISIBLE

        refreshSessionList()
    }

    private fun openSession(sessionDir: File) {

        if (nativeRunning.get()) {
            updateStatus("Stop the current camera session first")
            return
        }

        activeSessionDir = sessionDir
        activeSessionName = sessionDir.name
        activeCameraName = null

        synchronized(publishLock) {
            publishedFiles.clear()
            seenJpegNames.clear()
            sessionDir.listFiles()?.forEach { photo ->
                if (photo.isFile && isJpeg(photo)) seenJpegNames.add(photo.name)
            }
        }

        binding.activeSessionText.text = "Session: ${sessionDir.name}"
        updateSessionFrameControls(sessionDir)
        clearJpegPreview()

        binding.sessionSetupLayout.visibility = View.GONE
        binding.receiverLayout.visibility = View.VISIBLE

        updateDashboard()
        updateSessionStats()

        updateStatus("Session selected - waiting for camera")

        binding.root.post {
            detectCamera()
        }
    }

    // =====================================
    // UI
    // =====================================

    private fun setupButtons() {

        binding.stopButton.text = "Start Receiving"

        binding.stopButton.setOnClickListener {

            if (nativeRunning.get()) {
                requestSessionStop()
            } else {
                restartCameraReceiver()
            }
        }

        binding.transfersButton.setOnClickListener {
            startActivity(UploadActivity.intent(this, activeSessionDir))
        }

        binding.frameButton.setOnClickListener {
            activeSessionDir?.let { startActivity(FrameEditorActivity.intent(this, it)) }
        }

        binding.showLatestButton.setOnClickListener {
            activeSessionDir?.let(::showLatestJpegPreview)
        }

        binding.stopButton.isEnabled = false
    }

    private fun updateSessionFrameControls(directory: File) {
        val store = FrameDesignStore(directory)
        val enabled = runCatching { store.activeOverlay() != null }.getOrDefault(false)
        binding.frameStatus.setText(if (enabled) R.string.session_frame_on else R.string.session_frame_off)
        val previousSeconds = previewSeconds
        previewSeconds = store.previewDurationSeconds()
        binding.previewPlaceholderText.setText(if (previewSeconds == 0) R.string.session_preview_off else R.string.session_preview_ready)
        if (previewSeconds == 0 && previousSeconds != 0) clearJpegPreview()
    }

    // Restart receiver using the still-connected USB camera.
    // The previous native session closes its UsbDeviceConnection,
    // so a fresh connection and file descriptor are required.
    private fun restartCameraReceiver() {

        if (nativeRunning.get()) {
            return
        }

        if (activeSessionDir == null) {
            updateStatus("Create a session first")
            binding.stopButton.isEnabled = false
            return
        }

        val deviceName = activeUsbDeviceName

        val device = usbManager.deviceList.values.firstOrNull {
            it.deviceName == deviceName && isPtpInterface(it)
        }

        if (device == null) {
            activeUsbDeviceName = null
            activeCameraName = null

            binding.stopButton.text = "Start Receiving"
            binding.stopButton.isEnabled = false
            updateStatus("Camera disconnected - waiting for camera")
            return
        }

        if (!usbManager.hasPermission(device)) {
            binding.stopButton.isEnabled = false
            requestCameraPermission(device)
            return
        }

        openCameraDevice(device)
    }

    private fun updateDashboard() {

        binding.cameraNameText.text = "RuangFoto"

        binding.activeSessionText.text =
            "Session: ${activeSessionName ?: "—"}"

        binding.statusText.text = "Waiting for camera"

        binding.sessionPhotosText.text = "0"
        binding.sessionBytesText.text = "0 MB"
        binding.jpegCountText.text = "JPEG: 0"
        binding.rawCountText.text = "RAW: 0"
        binding.latestText.text = "Latest file: —"

        binding.previewImage.setImageDrawable(null)
        binding.previewImage.visibility = View.INVISIBLE
        binding.previewPlaceholderText.visibility = View.VISIBLE
    }

    private fun updateStatus(message: String) {

        if (activityDestroyed) return

        runOnUiThread {

            if (!activityDestroyed) {
                binding.statusText.text = message
            }
        }
    }

    // =====================================
    // SESSION STATISTICS
    // =====================================

    private fun getSessionFiles(): List<File> {

        val sessionDir = activeSessionDir ?: return emptyList()

        return sessionDir.listFiles()
            ?.filter { it.isFile && isSupportedPhoto(it) }
            ?: emptyList()
    }

    private fun updateSessionStats() {

        if (activityDestroyed) return

        val files = getSessionFiles()
        val jpegFiles = files.filter { isJpeg(it) }
        val rawFiles = files.filter { isRaw(it) }
        val totalBytes = files.sumOf { it.length() }

        val latest = files.maxByOrNull { it.lastModified() }

        runOnUiThread {

            if (activityDestroyed) return@runOnUiThread

            binding.sessionPhotosText.text = files.size.toString()

            binding.sessionBytesText.text = String.format(
                Locale.US,
                "%.1f MB",
                totalBytes / (1024.0 * 1024.0)
            )

            binding.jpegCountText.text = "JPEG: ${jpegFiles.size}"
            binding.rawCountText.text = "RAW: ${rawFiles.size}"
            binding.latestText.text = "Latest file: ${latest?.name ?: "—"}"
        }
    }

    // =====================================
    // IMAGE PREVIEW
    // =====================================

    private fun updateJpegPreview(directory: File, file: File, manual: Boolean = false) {

        if (activityDestroyed || !isJpeg(file)) return
        if (!file.exists() || file.length() <= 0L) return

        try {

            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFile(file.absolutePath, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return
            }

            val targetWidth =
                (resources.displayMetrics.widthPixels * 0.85f).toInt()

            val targetHeight =
                (resources.displayMetrics.heightPixels * 0.40f).toInt()

            val options = BitmapFactory.Options().apply {

                inSampleSize = calculateInSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    targetWidth,
                    targetHeight
                )

                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                options
            ) ?: return

            runOnUiThread {

                val seconds = if (manual && previewSeconds == 0) FrameDesignStore.DEFAULT_PREVIEW_SECONDS else previewSeconds
                if (activityDestroyed || activeSessionDir != directory || seconds == 0) {
                    bitmap.recycle()
                    return@runOnUiThread
                }

                binding.previewImage.setImageBitmap(bitmap)
                binding.previewImage.visibility = View.VISIBLE
                binding.previewPlaceholderText.visibility = View.GONE
                previewHandler.removeCallbacks(hidePreview)
                previewHandler.postDelayed(hidePreview, seconds * 1_000L)
            }

        } catch (_: Exception) {
        }
    }

    private fun showLatestJpegPreview(directory: File) {
        publishExecutor.execute {
            val photo = directory.listFiles()?.asSequence()?.filter { it.isFile && isJpeg(it) }
                ?.maxByOrNull { it.lastModified() }
            if (photo == null) {
                runOnUiThread {
                    if (!activityDestroyed && activeSessionDir == directory) {
                        Toast.makeText(this, R.string.session_preview_empty, Toast.LENGTH_SHORT).show()
                    }
                }
                return@execute
            }
            val framed = SessionFrame(directory).outputFor(photo)
            updateJpegPreview(directory, if (framed.isFile) framed else photo, manual = true)
        }
    }

    private fun clearJpegPreview() {
        previewHandler.removeCallbacks(hidePreview)
        binding.previewImage.setImageDrawable(null)
        binding.previewImage.visibility = View.GONE
        binding.previewPlaceholderText.visibility = View.VISIBLE
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {

        var sampleSize = 1

        if (targetWidth <= 0 || targetHeight <= 0) {
            return sampleSize
        }

        while (
            width / (sampleSize * 2) >= targetWidth &&
            height / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }

        return sampleSize
    }

    private fun isJpeg(file: File): Boolean {
        return file.extension.equals("jpg", true) ||
                file.extension.equals("jpeg", true)
    }

    private fun isRaw(file: File): Boolean {

        return file.extension.lowercase(Locale.ROOT) in setOf(
            "arw", "nef", "nrw", "cr2", "cr3",
            "dng", "raf", "orf", "rw2", "pef"
        )
    }

    // =====================================
    // USB RECEIVER
    // =====================================

    private fun registerUsbReceiver() {

        val filter = IntentFilter().apply {

            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                permissionReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }

        receiverRegistered = true
    }

    private fun getUsbDevice(intent: Intent): UsbDevice? {

        return if (Build.VERSION.SDK_INT >= 33) {

            intent.getParcelableExtra(
                UsbManager.EXTRA_DEVICE,
                UsbDevice::class.java
            )

        } else {

            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    // =====================================
    // USB CAMERA DETECTION
    // =====================================

    private fun getPtpCamera(device: UsbDevice): CameraInfo? {

        if (!isPtpInterface(device)) {
            return null
        }

        val vendorId = device.vendorId
        val productId = device.productId

        val usbName = device.productName
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val cameraName = usbName
            ?: String.format(
                Locale.US,
                "PTP Camera (VID: %04X / PID: %04X)",
                vendorId,
                productId
            )

        return CameraInfo(
            name = cameraName,
            vendorId = vendorId,
            productId = productId
        )
    }

    private fun isPtpInterface(device: UsbDevice): Boolean {

        for (i in 0 until device.interfaceCount) {

            val usbInterface = device.getInterface(i)

            if (
                usbInterface.interfaceClass == 6 &&
                usbInterface.interfaceSubclass == 1 &&
                usbInterface.interfaceProtocol == 1
            ) {
                return true
            }
        }

        return false
    }

    private fun getUsbInterfaceDescription(device: UsbDevice): String {

        val details = StringBuilder()

        details.append(
            "VID: 0x${device.vendorId.toString(16).uppercase()}\n"
        )

        details.append(
            "PID: 0x${device.productId.toString(16).uppercase()}\n"
        )

        for (i in 0 until device.interfaceCount) {

            val usbInterface = device.getInterface(i)

            details.append(
                "Interface $i: " +
                        "class=${usbInterface.interfaceClass}, " +
                        "subclass=${usbInterface.interfaceSubclass}, " +
                        "protocol=${usbInterface.interfaceProtocol}\n"
            )
        }

        return details.toString()
    }

    private fun detectCamera() {

        if (nativeRunning.get()) return

        if (activeSessionDir == null) {
            updateStatus("Create a session first")
            return
        }

        val cameraDevice = usbManager.deviceList.values
            .firstOrNull { isPtpInterface(it) }

        if (cameraDevice == null) {
            updateStatus("Waiting for PTP camera...")
            return
        }

        handleCameraDevice(cameraDevice)
    }

    private fun handleCameraDevice(device: UsbDevice) {

        if (nativeRunning.get()) return

        if (activeSessionDir == null) {
            updateStatus("Create a session first")
            return
        }

        val camera = getPtpCamera(device)

        if (camera == null) {

            updateStatus(
                "PTP interface not found.\n" +
                        getUsbInterfaceDescription(device)
            )

            return
        }

        updateStatus("${camera.name} detected")

        if (usbManager.hasPermission(device)) {
            openCameraDevice(device)
        } else {
            requestCameraPermission(device)
        }
    }

    // =====================================
    // USB PERMISSION
    // =====================================

    private fun requestCameraPermission(device: UsbDevice) {

        updateStatus("Requesting USB permission...")

        val permissionIntent = PendingIntent.getBroadcast(
            this,
            1001,
            Intent(usbPermissionAction).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
        )

        usbManager.requestPermission(device, permissionIntent)
    }

    // =====================================
    // PLUGIN SETUP
    // =====================================

    private fun setupPlugins() {

        try {

            val camlibsDir = File(pluginDir, "camlibs")
            val iolibsDir = File(pluginDir, "iolibs")

            camlibsDir.mkdirs()
            iolibsDir.mkdirs()

            outputDir.mkdirs()
            sessionsDir.mkdirs()

            copyAsset(
                "plugins/camlibs/ptp2.so",
                File(camlibsDir, "ptp2.so")
            )

            copyAsset(
                "plugins/iolibs/usb1.so",
                File(iolibsDir, "usb1.so")
            )

        } catch (e: Exception) {

            updateStatus("Plugin setup failed: ${e.message}")
        }
    }

    private fun copyAsset(
        assetPath: String,
        destination: File
    ) {

        assets.open(assetPath).use { input ->

            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }

    // =====================================
    // OPEN USB DEVICE
    // =====================================

    private fun openCameraDevice(device: UsbDevice) {

        if (nativeRunning.get()) return

        val sessionDir = activeSessionDir

        if (sessionDir == null) {
            updateStatus("Create a session first")
            return
        }

        val camera = getPtpCamera(device)

        if (camera == null) {

            updateStatus(
                "PTP interface not found:\n" +
                        getUsbInterfaceDescription(device)
            )

            return
        }

        updateStatus("Opening ${camera.name}...")

        val connection = usbManager.openDevice(device)

        if (connection == null) {
            updateStatus("Failed to open USB connection")
            return
        }

        val fd = connection.fileDescriptor

        if (fd < 0) {

            connection.close()
            updateStatus("Invalid USB file descriptor")
            return
        }

        usbConnection = connection
        activeUsbDeviceName = device.deviceName

        val camlibsDir = File(pluginDir, "camlibs")
        val iolibsDir = File(pluginDir, "iolibs")

        if (!sessionDir.exists() && !sessionDir.mkdirs()) {

            connection.close()
            usbConnection = null
            activeUsbDeviceName = null

            updateStatus("Cannot access session directory")
            return
        }

        activeCameraName = camera.name
        binding.cameraNameText.text = camera.name

        startNativeSession(
            connection,
            fd,
            camlibsDir.absolutePath,
            iolibsDir.absolutePath,
            sessionDir,
            camera.vendorId,
            camera.productId
        )
    }

    // =====================================
    // START NATIVE SESSION
    // =====================================

    private fun startNativeSession(
        connection: UsbDeviceConnection,
        fd: Int,
        camlibsPath: String,
        iolibsPath: String,
        destination: File,
        vendorId: Int,
        productId: Int
    ) {

        if (!nativeRunning.compareAndSet(false, true)) {

            connection.close()
            usbConnection = null

            return
        }

        stopRequested.set(false)
        resetStop()

        binding.stopButton.text = "Stop Receiving"
        binding.stopButton.isEnabled = false
        binding.statusText.text = "Starting camera receiver..."

        updateSessionStats()

        publishTask?.cancel(false)
        val frame = SessionFrame(destination)

        publishTask = publishExecutor.scheduleWithFixedDelay(
            {

                try {

                    publishNewFiles(destination, frame)
                    updateSessionStats()

                } catch (_: Exception) {
                }
            },
            1,
            1,
            TimeUnit.SECONDS
        )

        UploadService.watching = true
        activeSessionDir?.let { directory ->
            if (UploadStore.get(this).rule(directory).enabled && !UploadStore.get(this).paused) {
                runCatching { UploadService.startFromVisibleApp(this) }
            }
        }

        nativeExecutor.execute {

            var result = ""
            var errorMessage: String? = null

            try {

                runOnUiThread {

                    if (!activityDestroyed) {

                        binding.stopButton.isEnabled = true
                        binding.statusText.text = "USB Connected"
                    }
                }

                result = testUsbFd(
                    fd,
                    camlibsPath,
                    iolibsPath,
                    destination.absolutePath,
                    vendorId,
                    productId
                )

            } catch (e: UnsatisfiedLinkError) {

                errorMessage = "Native link error: ${e.message}"

            } catch (e: Exception) {

                errorMessage = "${e.javaClass.simpleName}: ${e.message}"

            } finally {

                try {
                    connection.close()
                } catch (_: Exception) {
                }

                if (usbConnection === connection) {
                    usbConnection = null
                }
                publishTask?.cancel(false)
                publishTask = null
                try {
                    publishNewFiles(destination, frame)
                } catch (_: Exception) {
                }

                nativeRunning.set(false)
                UploadService.watching = false
                stopRequested.set(false)

            }

            val connectedDevice = activeUsbDeviceName?.let { savedDeviceName ->
                usbManager.deviceList.values.firstOrNull {
                    it.deviceName == savedDeviceName &&
                            isPtpInterface(it) &&
                            usbManager.hasPermission(it)
                }
            }

            if (connectedDevice == null) {
                activeUsbDeviceName = null
                activeCameraName = null
            }

            runOnUiThread {

                if (!activityDestroyed) {

                    binding.stopButton.text = "Start Receiving"
                    binding.stopButton.isEnabled = connectedDevice != null

                    binding.statusText.text =
                        if (errorMessage != null) {
                            "Session ended with error: $errorMessage"
                        } else if (connectedDevice != null) {
                            "Connected - Receiver stopped"
                        } else {
                            "Session stopped - waiting for camera"
                        }

                    updateSessionStats()
                    refreshSessionList()
                }
            }
        }
    }

    // =====================================
    // STOP SESSION
    // =====================================

    private fun requestSessionStop() {

        if (!nativeRunning.get()) {
            updateStatus("No active camera session")
            return
        }

        if (!stopRequested.compareAndSet(false, true)) {
            return
        }

        binding.stopButton.isEnabled = false
        binding.statusText.text = "Stopping safely..."

        try {

            requestStop()

        } catch (e: Exception) {

            updateStatus("Stop request error: ${e.message}")
        }
    }

    // =====================================
    // FILE SCANNING & PUBLISHING
    // =====================================

    private fun isSupportedPhoto(file: File): Boolean {
        return isJpeg(file) || isRaw(file)
    }

    private fun publishNewFiles(directory: File, frame: SessionFrame) {
        synchronized(publishLock) {
            if (activeSessionDir != directory) return
            // The receiver atomically renames complete downloads; temporary .part files are not photos.
            val originals = directory.listFiles()?.filter { it.isFile && isSupportedPhoto(it) } ?: return
            val newJpegs = originals.filter { isJpeg(it) && seenJpegNames.add(it.name) }
            if (!frame.renderNewPhotos(newJpegs)) {
                runOnUiThread {
                    if (!activityDestroyed && activeSessionDir == directory) {
                        Toast.makeText(this, R.string.session_frame_render_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (previewSeconds > 0) newJpegs.maxByOrNull { it.lastModified() }?.let { photo ->
                val framed = frame.outputFor(photo)
                updateJpegPreview(directory, if (framed.isFile) framed else photo)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val galleryPath = "${Environment.DIRECTORY_PICTURES}/RuangFoto/Sessions/${directory.name}/"
                publishToGallery(originals, galleryPath)
                frame.outputDirectory.listFiles()?.let { files ->
                    publishToGallery(files.filter { it.isFile && isJpeg(it) }, "${galleryPath}${frame.outputDirectory.name}/")
                }
            }
        }
    }

    private fun publishToGallery(files: Iterable<File>, relativePath: String) {
        for (file in files) {
            val key = file.absolutePath
            if (key in publishedFiles || file.length() <= 0) continue
            if (isAlreadyPublished(file, relativePath) || publishSingleFile(file, relativePath)) {
                publishedFiles.add(key)
            }
        }
    }

    // =====================================
    // CHECK EXISTING MEDIASTORE FILE
    // =====================================

    private fun isAlreadyPublished(
        sourceFile: File,
        relativePath: String
    ): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        return try {

            val collection =
                MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                )

            val projection = arrayOf(MediaStore.MediaColumns._ID)

            val selection =
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"

            val selectionArgs = arrayOf(
                sourceFile.name,
                relativePath
            )

            contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->

                cursor.moveToFirst()

            } ?: false

        } catch (_: Exception) {

            false
        }
    }

    // =====================================
    // PUBLISH SINGLE FILE
    // =====================================

    private fun publishSingleFile(
        sourceFile: File,
        relativePath: String
    ): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        var destinationUri: Uri? = null

        try {

            val extension =
                sourceFile.extension.lowercase(Locale.ROOT)

            val mimeType = when (extension) {

                "jpg", "jpeg" -> "image/jpeg"
                "arw" -> "image/x-sony-arw"
                "nef", "nrw" -> "image/x-nikon-nef"
                "cr2" -> "image/x-canon-cr2"
                "cr3" -> "image/x-canon-cr3"
                "dng" -> "image/x-adobe-dng"
                "raf" -> "image/x-fuji-raf"
                "orf" -> "image/x-olympus-orf"
                "rw2" -> "image/x-panasonic-rw2"
                "pef" -> "image/x-pentax-pef"

                else -> return false
            }

            val values = ContentValues().apply {

                put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val collection =
                MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                )

            destinationUri = contentResolver.insert(
                collection,
                values
            ) ?: return false

            contentResolver.openOutputStream(
                destinationUri,
                "w"
            )?.use { output ->

                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }

            } ?: throw Exception("Cannot open output stream")

            val publishValues = ContentValues().apply {

                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }

            val updated = contentResolver.update(
                destinationUri,
                publishValues,
                null,
                null
            )

            if (updated <= 0) {
                throw Exception("MediaStore publish failed")
            }

            return true

        } catch (_: Exception) {

            destinationUri?.let {

                try {
                    contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }

            return false
        }
    }

    // =====================================
    // JNI
    // =====================================

    external fun testUsbFd(
        fd: Int,
        camlibsPath: String,
        iolibsPath: String,
        outputPath: String,
        vendorId: Int,
        productId: Int
    ): String

    external fun requestStop()
    external fun resetStop()

    // =====================================
    // DESTROY
    // =====================================


    override fun onDestroy() {

        activityDestroyed = true
        previewHandler.removeCallbacks(hidePreview)

        if (nativeRunning.get()) {

            try {
                requestStop()
            } catch (_: Exception) {
            }
        }

        if (receiverRegistered) {

            try {
                unregisterReceiver(permissionReceiver)
            } catch (_: Exception) {
            }

            receiverRegistered = false
        }

        publishTask?.cancel(false)
        publishExecutor.shutdownNow()
        nativeExecutor.shutdown()

        super.onDestroy()
    }

    companion object {

        init {
            System.loadLibrary("sonygphototest")
        }
    }
}
