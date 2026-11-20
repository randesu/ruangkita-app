package com.ruangfoto.app

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ruangfoto.app.databinding.ActivityFrameEditorBinding
import com.ruangfoto.app.databinding.DialogFrameCanvasBinding
import java.io.File
import java.io.IOException
import java.math.RoundingMode
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-session frame workbench: an imported PNG, text/emoji layers, their stacking order,
 * revisions and the shot preview duration. Camera capture lives in MainActivity; saving here
 * only writes the session design, so it never interrupts a running session.
 */
class FrameEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFrameEditorBinding
    private lateinit var store: FrameDesignStore

    private var draft = FrameDesign()
    private var savedDesign: FrameDesign? = null
    private var loadedRevisionId: String? = null
    private var previewDuration = FrameDesignStore.DEFAULT_PREVIEW_SECONDS
    private var selectedZ = -1
    private var pendingFontLabel = -1
    private var busy = false
    private var busyMessage = 0
    private var bindingFields = false
    private var textEditRecorded = false
    private var draggingZ = -1
    private var draggingMoved = false
    @Volatile private var destroyed = false

    private var previewBitmap: Bitmap? = null
    private var thumbnailBitmap: Bitmap? = null
    private var thumbnailRef: String? = null
    private val layerNames = HashMap<Int, TextView>()

    private val undoStack = ArrayDeque<FrameDesign>()
    private val redoStack = ArrayDeque<FrameDesign>()

    private val worker = Executors.newSingleThreadExecutor()
    private val pendingPreview = AtomicReference<FrameDesign?>(null)
    private val previewRunning = AtomicBoolean(false)

    private val overlayPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importOverlay(uri)
    }

    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = pendingFontLabel
        pendingFontLabel = -1
        if (uri != null) importFont(index, uri)
    }

    private val labelTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(text: Editable?) {
            if (bindingFields || busy) return
            val index = selectedLabelIndex() ?: return
            val value = text?.toString().orEmpty()
            if (draft.labels[index].text == value) return
            if (!textEditRecorded) {
                recordEdit()
                textEditRecorded = true
            }
            updateLabel(index) { it.copy(text = value) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val directory = intent.getStringExtra(EXTRA_DIRECTORY)?.let(::File)
        if (directory == null) {
            finish()
            return
        }
        store = FrameDesignStore(directory)

        binding = ActivityFrameEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.editorWorkspace.arrangeWorkspace()
        binding.toolbar.subtitle = directory.name
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            binding.editorStage.visibility = if (insets.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            insets
        }

        binding.toolbar.setNavigationOnClickListener { leaveEditor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveEditor()
        })

        setupSpinners()
        setupActions()
        setupDuration()

        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
            bindAll()
            requestPreview()
            loadActive(keepDraft = true)
        } else {
            loadActive(keepDraft = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::binding.isInitialized) return
        outState.putBundle(STATE_DRAFT, designToBundle(draft))
        savedDesign?.let { outState.putBundle(STATE_SAVED, designToBundle(it)) }
        outState.putInt(STATE_SELECTED, selectedZ)
        outState.putString(STATE_REVISION, loadedRevisionId)
        outState.putInt(STATE_PENDING_FONT, pendingFontLabel)
    }

    override fun onDestroy() {
        destroyed = true
        // shutdown(), not shutdownNow(): an in-flight save should finish writing.
        worker.shutdown()
        previewBitmap?.recycle()
        previewBitmap = null
        thumbnailBitmap?.recycle()
        thumbnailBitmap = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------------------------
    // Setup

    private fun setupSpinners() {
        val canvasAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            FrameCanvas.entries.map { it.title })
        canvasAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.canvasSpinner.adapter = canvasAdapter
        binding.canvasSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (bindingFields || busy) return
                val canvas = FrameCanvas.entries.getOrNull(position) ?: return
                if (draft.canvas == canvas) return
                if (canvas == FrameCanvas.CUSTOM) {
                    // The spinner already moved; the dialog decides the size or reverts it.
                    showCustomSizeDialog()
                    return
                }
                recordEdit()
                draft = draft.copy(canvas = canvas)
                requestPreview()
                updateButtons()
                updateStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupActions() {
        binding.overlayChooseButton.setOnClickListener { overlayPicker.launch(arrayOf("image/png")) }
        binding.overlayRemoveButton.setOnClickListener { removeOverlay() }
        binding.addLabelButton.setOnClickListener { addLabel() }
        binding.removeLabelButton.setOnClickListener { removeSelectedLayer() }
        binding.colorButton.setOnClickListener { chooseColor() }
        binding.fontButton.setOnClickListener { chooseFont() }
        binding.customSizeButton.setOnClickListener { showCustomSizeDialog() }
        binding.undoButton.setOnClickListener { undo() }
        binding.redoButton.setOnClickListener { redo() }
        binding.historyButton.setOnClickListener { showHistory() }
        binding.saveButton.setOnClickListener {
            saveFrame(getString(if (loadedRevisionId == null) R.string.frame_editor_saved else R.string.frame_editor_applied))
        }
        binding.disableButton.setOnClickListener { confirmDisable() }

        listOf(
            binding.alignTopLeftButton to (0f to 0f),
            binding.alignTopButton to (0.5f to 0f),
            binding.alignTopRightButton to (1f to 0f),
            binding.alignLeftButton to (0f to 0.5f),
            binding.alignCenterButton to (0.5f to 0.5f),
            binding.alignRightButton to (1f to 0.5f),
            binding.alignBottomLeftButton to (0f to 1f),
            binding.alignBottomButton to (0.5f to 1f),
            binding.alignBottomRightButton to (1f to 1f)
        ).forEach { (button, position) ->
            button.setOnClickListener { alignImage(position.first, position.second) }
        }

        binding.labelTextInput.addTextChangedListener(labelTextWatcher)
        binding.labelTextInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) textEditRecorded = false
        }

        setupSeekBars()
        binding.previewImage.setOnTouchListener { _, event -> handlePreviewTouch(event) }
    }

    private fun setupSeekBars() {
        binding.sizeSeek.setOnSeekBarChangeListener(seekListener(
            onProgress = { progress ->
                if (selectedIsImage()) {
                    val percent = progress + MIN_IMAGE_PERCENT
                    binding.sizeValue.text = getString(R.string.frame_editor_percent, percent)
                    updateImageLayer { it.copy(scale = percent / 100f) }
                } else {
                    val percent = progress + MIN_SIZE_PERCENT
                    binding.sizeValue.text = getString(R.string.frame_editor_percent, percent)
                    updateSelectedLabel { it.copy(size = percent / 100f) }
                }
            }
        ))

        binding.xSeek.max = 100
        binding.xSeek.setOnSeekBarChangeListener(seekListener(
            onProgress = { progress ->
                binding.xValue.text = getString(R.string.frame_editor_percent, progress)
                if (selectedIsImage()) updateImageLayer { it.copy(x = progress / 100f) }
                else updateSelectedLabel { it.copy(x = progress / 100f) }
            }
        ))

        binding.ySeek.max = 100
        binding.ySeek.setOnSeekBarChangeListener(seekListener(
            onProgress = { progress ->
                binding.yValue.text = getString(R.string.frame_editor_percent, progress)
                if (selectedIsImage()) updateImageLayer { it.copy(y = progress / 100f) }
                else updateSelectedLabel { it.copy(y = progress / 100f) }
            }
        ))
    }

    private fun seekListener(onProgress: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            private var tracking = false
            private var recorded = false

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (bindingFields || !fromUser || selectedZ !in 0 until draft.layerCount) return
                if (!recorded) recordEdit()
                recorded = tracking
                onProgress(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) { tracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                tracking = false
                recorded = false
            }
        }

    private fun setupDuration() {
        binding.previewDurationSeek.max = FrameDesignStore.MAX_PREVIEW_SECONDS
        binding.previewDurationValue.text = durationText(previewDuration)
        binding.previewDurationSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var tracking = false

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.previewDurationValue.text = durationText(progress)
                    if (!tracking) saveDuration(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) { tracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                tracking = false
                saveDuration(seekBar.progress)
            }
        })
    }

    private fun saveDuration(seconds: Int) {
        if (seconds == previewDuration) return
        previewDuration = seconds
        background {
            val result = runCatching { store.setPreviewDurationSeconds(seconds) }
            if (result.isFailure) {
                val persisted = store.previewDurationSeconds()
                runOnUiThread {
                    if (!destroyed && previewDuration == seconds) {
                        showDuration(persisted)
                        showError(getString(R.string.frame_editor_duration_failed))
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // State

    private fun restoreState(state: Bundle) {
        state.getBundle(STATE_DRAFT)?.let { draft = designFromBundle(it) }
        savedDesign = state.getBundle(STATE_SAVED)?.let(::designFromBundle)
        selectedZ = state.getInt(STATE_SELECTED, -1)
        loadedRevisionId = state.getString(STATE_REVISION)
        pendingFontLabel = state.getInt(STATE_PENDING_FONT, -1)
    }

    private fun loadActive(keepDraft: Boolean) {
        setBusy(true, R.string.frame_editor_status_loading)
        background {
            val active = runCatching { store.activeDesign() }
            val duration = runCatching { store.previewDurationSeconds() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                active.onSuccess { design ->
                    savedDesign = design
                    if (!keepDraft) draft = design ?: FrameDesign()
                    bindAll()
                }.onFailure { showError(getString(R.string.frame_editor_load_failed)) }
                duration.onSuccess(::showDuration)
                requestPreview()
            }
        }
    }

    private fun bindAll() {
        draft = draft.withClampedOrder()
        withoutFieldEvents { binding.canvasSpinner.setSelection(FrameCanvas.entries.indexOf(draft.canvas)) }
        bindSelection()
        updateButtons()
        updateStatus()
    }

    private fun bindSelection() {
        if (selectedZ !in 0 until draft.layerCount) selectedZ = draft.topZ
        val index = selectedLabelIndex()
        val label = index?.let(draft.labels::get)
        val hasLayer = selectedZ in 0 until draft.layerCount

        binding.labelPanel.visibility = if (hasLayer) View.VISIBLE else View.GONE
        binding.labelTextLayout.visibility = if (label == null) View.GONE else View.VISIBLE
        binding.colorButton.visibility = if (label == null) View.GONE else View.VISIBLE
        binding.fontButton.visibility = if (label == null) View.GONE else View.VISIBLE
        binding.alignmentPanel.visibility =
            if (hasLayer && label == null && draft.overlay != null) View.VISIBLE else View.GONE
        binding.sizeLabel.setText(if (label == null) R.string.frame_editor_image_size else R.string.frame_editor_size)
        binding.removeLabelButton.setText(
            if (label == null) R.string.frame_editor_remove_image else R.string.frame_editor_remove_label)

        if (hasLayer) {
            withoutFieldEvents {
                if (label != null) {
                    if (binding.labelTextInput.text.toString() != label.text) {
                        binding.labelTextInput.setText(label.text)
                        binding.labelTextInput.setSelection(binding.labelTextInput.length())
                    }
                    val sizePercent = (label.size * 100).roundToInt().coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)
                    binding.sizeSeek.max = MAX_SIZE_PERCENT - MIN_SIZE_PERCENT
                    binding.sizeSeek.progress = sizePercent - MIN_SIZE_PERCENT
                    binding.sizeValue.text = getString(R.string.frame_editor_percent, sizePercent)
                } else {
                    val scalePercent = (draft.imageLayer.scale * 100).roundToInt()
                        .coerceIn(MIN_IMAGE_PERCENT, MAX_IMAGE_PERCENT)
                    binding.sizeSeek.max = MAX_IMAGE_PERCENT - MIN_IMAGE_PERCENT
                    binding.sizeSeek.progress = scalePercent - MIN_IMAGE_PERCENT
                    binding.sizeValue.text = getString(R.string.frame_editor_percent, scalePercent)
                }

                val x = label?.x ?: draft.imageLayer.x
                val xPercent = (x * 100).roundToInt().coerceIn(0, 100)
                binding.xSeek.progress = xPercent
                binding.xValue.text = getString(R.string.frame_editor_percent, xPercent)

                val y = label?.y ?: draft.imageLayer.y
                val yPercent = (y * 100).roundToInt().coerceIn(0, 100)
                binding.ySeek.progress = yPercent
                binding.yValue.text = getString(R.string.frame_editor_percent, yPercent)

                if (label != null) {
                    val colorName = LabelColor.entries.firstOrNull { it.argb == label.color }
                        ?.let { getString(it.labelRes) }
                        ?: getString(R.string.frame_editor_custom)
                    binding.colorButton.text = getString(R.string.frame_editor_color_value, colorName)
                    binding.fontButton.text = getString(R.string.frame_editor_font_value,
                        getString(if (label.font == null) R.string.frame_editor_system else R.string.frame_editor_custom))
                }
            }
        }

        textEditRecorded = false
        rebuildLayerPanel()
        updateSelectionBox()
        updateButtons()
    }

    private fun updateButtons() {
        val hasLayer = selectedZ in 0 until draft.layerCount
        val hasLabel = selectedLabel() != null
        val content = hasContent(draft)
        binding.saveButton.isEnabled = !busy && content && (isDirty() || loadedRevisionId != null)
        binding.saveButton.setText(if (loadedRevisionId == null) R.string.frame_editor_save else R.string.frame_editor_apply_revision)
        binding.historyButton.isEnabled = !busy
        binding.disableButton.isEnabled = !busy && savedDesign != null
        binding.undoButton.isEnabled = !busy && undoStack.isNotEmpty()
        binding.redoButton.isEnabled = !busy && redoStack.isNotEmpty()
        binding.addLabelButton.isEnabled = !busy && draft.labels.size < MAX_LABELS
        binding.overlayChooseButton.isEnabled = !busy
        binding.overlayRemoveButton.isEnabled = !busy && draft.overlay != null
        binding.canvasSpinner.isEnabled = !busy
        binding.customSizeButton.isEnabled = !busy
        binding.customSizeButton.visibility =
            if (draft.canvas == FrameCanvas.CUSTOM) View.VISIBLE else View.GONE
        val print = draft.printSize
        val sizeText = if (print != null) {
            getString(R.string.frame_editor_print_size_value, formatMeasure(print.width),
                formatMeasure(print.height), print.unit.symbol, print.dpi)
        } else getString(R.string.frame_editor_custom_size_value, draft.customSize.width, draft.customSize.height)
        binding.customSizeButton.text = getString(R.string.frame_editor_custom_size, sizeText)
        binding.removeLabelButton.isEnabled = !busy && hasLayer
        binding.colorButton.isEnabled = !busy && hasLabel
        binding.fontButton.isEnabled = !busy && hasLabel
        binding.labelTextInput.isEnabled = !busy && hasLabel
        binding.sizeSeek.isEnabled = !busy && hasLayer
        binding.xSeek.isEnabled = !busy && hasLayer
        binding.ySeek.isEnabled = !busy && hasLayer
        binding.alignmentPanel.isEnabled = !busy && hasLayer && selectedIsImage()
        binding.previewDurationSeek.isEnabled = !busy
        binding.overlayChooseButton.setText(
            if (draft.overlay != null) R.string.frame_editor_replace_png else R.string.frame_editor_choose_png)
        binding.previewEmpty.visibility = if (content) View.GONE else View.VISIBLE
    }

    private fun updateStatus(message: String? = null, error: Boolean = false) {
        if (message != null) {
            binding.statusText.text = message
            binding.statusText.setTextColor(ContextCompat.getColor(this,
                if (error) R.color.transfer_stop else R.color.transfer_text))
            return
        }
        binding.statusText.text = when {
            busy && busyMessage != 0 -> getString(busyMessage)
            busy -> getString(R.string.frame_editor_status_working)
            loadedRevisionId != null -> getString(R.string.frame_editor_revision_loaded)
            isDirty() -> getString(R.string.frame_editor_status_unsaved)
            savedDesign != null -> getString(R.string.frame_editor_status_enabled)
            else -> getString(R.string.frame_editor_status_disabled)
        }
        binding.statusText.setTextColor(ContextCompat.getColor(this,
            if (savedDesign != null) R.color.transfer_accent else R.color.transfer_muted))
    }

    private fun showError(message: String) = updateStatus(message, error = true)

    private fun setBusy(value: Boolean, message: Int = 0) {
        busy = value
        busyMessage = if (value) message else 0
        binding.statusProgress.visibility = if (value) View.VISIBLE else View.GONE
        updateButtons()
        updateStatus()
    }

    private fun showDuration(seconds: Int) {
        previewDuration = seconds.coerceIn(0, FrameDesignStore.MAX_PREVIEW_SECONDS)
        binding.previewDurationSeek.progress = previewDuration
        binding.previewDurationValue.text = durationText(previewDuration)
    }

    private fun durationText(seconds: Int): String =
        if (seconds <= 0) getString(R.string.frame_editor_preview_off)
        else getString(R.string.frame_editor_preview_seconds, seconds)

    // -------------------------------------------------------------------------------------
    // Editing

    private fun recordEdit() {
        if (undoStack.size >= MAX_UNDO) undoStack.removeFirst()
        undoStack.addLast(draft)
        redoStack.clear()
        textEditRecorded = false
        loadedRevisionId = null
        updateButtons()
        updateStatus()
    }

    private fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(draft)
        loadedRevisionId = null
        restoreDraft(previous)
        updateStatus()
    }

    private fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(draft)
        loadedRevisionId = null
        restoreDraft(next)
        updateStatus()
    }

    private fun restoreDraft(design: FrameDesign) {
        draft = design.withClampedOrder()
        if (selectedZ !in 0 until draft.layerCount) selectedZ = draft.topZ
        withoutFieldEvents { binding.canvasSpinner.setSelection(FrameCanvas.entries.indexOf(draft.canvas)) }
        bindSelection()
        requestPreview()
        updateButtons()
    }

    private fun selectedLabelIndex(): Int? =
        if (selectedZ in 0 until draft.layerCount) draft.labelIndexAt(selectedZ) else null

    private fun selectedLabel(): FrameLabel? = selectedLabelIndex()?.let(draft.labels::get)

    private fun selectedIsImage(): Boolean = draft.isImageLayer(selectedZ)

    private fun updateSelectedLabel(change: (FrameLabel) -> FrameLabel) {
        selectedLabelIndex()?.let { updateLabel(it, change) }
    }

    private fun updateLabel(index: Int, change: (FrameLabel) -> FrameLabel) {
        if (index !in draft.labels.indices) return
        draft = draft.copy(labels = draft.labels.mapIndexed { i, label ->
            if (i == index) change(label) else label
        })
        refreshLayerName(draft.zForLabel(index), index)
        requestPreview()
        updateButtons()
    }

    private fun alignImage(x: Float, y: Float) {
        if (busy || !selectedIsImage()) return
        if (draft.imageLayer.x == x && draft.imageLayer.y == y) return
        recordEdit()
        updateImageLayer { it.copy(x = x, y = y) }
        bindSelection()
        updateStatus()
    }

    private fun updateImageLayer(change: (FramePlacement) -> FramePlacement) {
        if (!selectedIsImage()) return
        draft = draft.copy(imageLayer = change(draft.imageLayer))
        requestPreview()
        updateButtons()
    }

    private fun selectLayer(z: Int) {
        if (busy || z !in 0 until draft.layerCount) return
        selectedZ = z
        bindSelection()
    }

    private fun addLabel() {
        if (draft.labels.size >= MAX_LABELS) return
        recordEdit()
        draft = draft.copy(labels = draft.labels + FrameLabel(getString(R.string.frame_editor_new_label)))
        selectedZ = draft.zForLabel(draft.labels.lastIndex)
        bindSelection()
        requestPreview()
        binding.labelTextInput.requestFocus()
        binding.labelTextInput.selectAll()
    }

    private fun removeSelectedLayer() {
        if (selectedZ !in 0 until draft.layerCount) return
        recordEdit()
        val index = draft.labelIndexAt(selectedZ)
        draft = if (index == null) {
            draft.copy(overlay = null).withClampedOrder()
        } else {
            draft.withLabelRemoved(index)
        }
        if (index == null) thumbnailRef = null
        selectedZ = selectedZ.coerceAtMost(draft.topZ)
        bindSelection()
        requestPreview()
        updateStatus()
    }

    private fun removeOverlay() {
        if (draft.overlay == null) return
        recordEdit()
        draft = draft.copy(overlay = null).withClampedOrder()
        thumbnailRef = null
        if (selectedZ !in 0 until draft.layerCount) selectedZ = draft.topZ
        bindSelection()
        requestPreview()
        updateStatus()
    }

    private fun moveLayer(z: Int, delta: Int) {
        if (busy) return
        val moved = draft.layerMoved(z, delta)
        if (moved == draft) return
        recordEdit()
        draft = moved
        selectedZ = z + delta
        bindSelection()
        requestPreview()
        updateStatus()
    }

    private fun toggleLayerVisibility(z: Int) {
        if (busy || z !in 0 until draft.layerCount) return
        recordEdit()
        val index = draft.labelIndexAt(z)
        draft = if (index == null) {
            draft.copy(imageLayer = draft.imageLayer.copy(visible = !draft.imageLayer.visible))
        } else {
            val label = draft.labels[index]
            draft.copy(labels = draft.labels.toMutableList().apply { set(index, label.copy(visible = !label.visible)) })
        }
        bindSelection()
        requestPreview()
        updateStatus()
    }

    private fun chooseColor() {
        val label = selectedLabel() ?: return
        val checked = LabelColor.entries.indexOfFirst { it.argb == label.color }
        val names = Array<CharSequence>(LabelColor.entries.size) { getString(LabelColor.entries[it].labelRes) }
        AlertDialog.Builder(this)
            .setTitle(R.string.frame_editor_color)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val color = LabelColor.entries[which].argb
                if (color != label.color) {
                    recordEdit()
                    updateSelectedLabel { it.copy(color = color) }
                    bindSelection()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseFont() {
        val label = selectedLabel() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.frame_editor_font_dialog_title)
            .setItems(arrayOf(getString(R.string.frame_editor_font_system),
                getString(R.string.frame_editor_font_pick))) { _, which ->
                if (which == 0) {
                    if (label.font != null) {
                        recordEdit()
                        updateSelectedLabel { it.copy(font = null) }
                        bindSelection()
                    }
                } else {
                    pendingFontLabel = selectedLabelIndex() ?: return@setItems
                    fontPicker.launch(arrayOf("*/*"))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------------------------------
    // Canvas size

    private fun showCustomSizeDialog() {
        val fields = DialogFrameCanvasBinding.inflate(layoutInflater)
        val current = draft.customSize
        val print = draft.printSize
        var unit = print?.unit ?: FrameUnit.PIXELS
        val divisor = gcd(current.width, current.height)
        fields.ratioWidth.setText((current.width / divisor).toString())
        fields.ratioHeight.setText((current.height / divisor).toString())
        fields.sizeWidth.setText(print?.let { formatMeasure(it.width) } ?: current.width.toString())
        fields.sizeHeight.setText(print?.let { formatMeasure(it.height) } ?: current.height.toString())
        fields.printDpi.setText((print?.dpi ?: DEFAULT_PRINT_DPI).toString())
        fields.sizeUnit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            FrameUnit.entries.map { it.symbol })
        fields.sizeUnit.setSelection(unit.ordinal)
        if (draft.canvas == FrameCanvas.CUSTOM) fields.sizeModeGroup.check(R.id.dimensionsMode)

        fun dimensionsSelected() = fields.sizeModeGroup.checkedRadioButtonId == R.id.dimensionsMode
        fun enteredDpi() = fields.printDpi.text.toString().toIntOrNull()?.takeIf { it in 1..MAX_PRINT_DPI }

        fun printSize(): FramePrintSize? {
            if (!dimensionsSelected() || unit == FrameUnit.PIXELS) return null
            val dpi = enteredDpi() ?: return null
            val width = parseMeasure(fields.sizeWidth.text) ?: return null
            val height = parseMeasure(fields.sizeHeight.text) ?: return null
            return FramePrintSize(width, height, unit, dpi)
        }

        fun chosenSize(printSize: FramePrintSize?): FrameSize? {
            val size = if (!dimensionsSelected()) {
                val width = fields.ratioWidth.text.toString().toIntOrNull() ?: return null
                val height = fields.ratioHeight.text.toString().toIntOrNull() ?: return null
                if (width !in 1..MAX_RATIO || height !in 1..MAX_RATIO) return null
                customCanvasSizeFromRatio(width, height, MAX_CUSTOM_SIDE)
            } else if (unit == FrameUnit.PIXELS) {
                val width = fields.sizeWidth.text.toString().toIntOrNull() ?: return null
                val height = fields.sizeHeight.text.toString().toIntOrNull() ?: return null
                FrameSize(width, height)
            } else printSize?.toPixels() ?: return null
            return size.takeIf { isValidCustomSize(it) && it.width >= MIN_CUSTOM_SIDE && it.height >= MIN_CUSTOM_SIDE }
        }

        fun refreshDialog() {
            val dimensions = dimensionsSelected()
            val usesDpi = dimensions && unit != FrameUnit.PIXELS
            fields.ratioRow.visibility = if (dimensions) View.GONE else View.VISIBLE
            fields.dimensionsFields.visibility = if (dimensions) View.VISIBLE else View.GONE
            fields.printDpi.visibility = if (usesDpi) View.VISIBLE else View.GONE
            val size = chosenSize(printSize())
            fields.sizeHint.text = when {
                usesDpi && enteredDpi() == null -> getString(R.string.frame_editor_print_dpi_invalid, MAX_PRINT_DPI)
                size == null -> getString(R.string.frame_editor_custom_size_invalid)
                else -> getString(R.string.frame_editor_custom_size_hint, size.width, size.height)
            }
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) = refreshDialog()
        }
        listOf(fields.ratioWidth, fields.ratioHeight, fields.sizeWidth, fields.sizeHeight, fields.printDpi)
            .forEach { it.addTextChangedListener(watcher) }
        fields.sizeModeGroup.setOnCheckedChangeListener { _, _ -> refreshDialog() }
        fields.sizeUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val next = FrameUnit.entries[position]
                if (next == unit) return
                val dpi = enteredDpi() ?: DEFAULT_PRINT_DPI
                val factor = unit.pixelsPerUnit(dpi) / next.pixelsPerUnit(dpi)
                unit = next
                for (field in listOf(fields.sizeWidth, fields.sizeHeight)) {
                    val converted = parseMeasure(field.text)?.times(factor) ?: continue
                    field.setText(if (unit == FrameUnit.PIXELS) converted.roundToInt().toString() else
                        converted.toBigDecimal().setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                }
                refreshDialog()
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        refreshDialog()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.frame_editor_custom_size_title)
            .setView(fields.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val printSize = printSize()
                val size = chosenSize(printSize)
                if (size == null) {
                    Toast.makeText(this, fields.sizeHint.text, Toast.LENGTH_SHORT).show()
                } else {
                    applyCustomSize(size, printSize)
                    dialog.dismiss()
                }
            }
        }
        // Choosing Custom from the spinner only sticks once a size is confirmed.
        dialog.setOnDismissListener {
            if (draft.canvas != FrameCanvas.CUSTOM) {
                withoutFieldEvents {
                    binding.canvasSpinner.setSelection(FrameCanvas.entries.indexOf(draft.canvas))
                }
            }
        }
        dialog.show()
    }

    private fun parseMeasure(text: CharSequence): Double? =
        text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 1e9 }

    private fun formatMeasure(value: Double): String =
        value.toBigDecimal().stripTrailingZeros().toPlainString()

    private fun applyCustomSize(size: FrameSize, printSize: FramePrintSize?) {
        if (draft.canvas == FrameCanvas.CUSTOM && draft.customSize == size && draft.printSize == printSize) return
        recordEdit()
        draft = draft.copy(canvas = FrameCanvas.CUSTOM, customSize = size, printSize = printSize)
        requestPreview()
        updateButtons()
        updateStatus()
    }

    // -------------------------------------------------------------------------------------
    // Layer panel

    private fun rebuildLayerPanel() {
        binding.layerList.removeAllViews()
        layerNames.clear()
        if (draft.layerCount == 0) {
            binding.layerList.visibility = View.GONE
            return
        }
        binding.layerList.visibility = View.VISIBLE
        for (z in draft.topZ downTo 0) binding.layerList.addView(layerRow(z))
    }

    private fun layerRow(z: Int): View {
        val index = draft.labelIndexAt(z)
        val label = index?.let(draft.labels::get)
        val name = layerName(index)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            isClickable = true
            isFocusable = true
            setPadding(dp(4f), 0, dp(4f), 0)
            background = rowBackground(z == selectedZ)
            contentDescription = rowDescription(name, z == selectedZ, draft.isVisible(z))
            setOnClickListener { selectLayer(z) }
        }

        if (label == null) {
            val thumb = overlayThumbnail()
            if (thumb != null) {
                row.addView(ImageView(this).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = name
                }, LinearLayout.LayoutParams(dp(36f), dp(36f)))
            } else {
                row.addView(layerBadge("PNG"))
            }
        } else {
            row.addView(layerBadge("T"))
        }

        val title = TextView(this).apply {
            text = name
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@FrameEditorActivity, R.color.transfer_text))
            textSize = 15f
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        layerNames[z] = title
        row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(rowAction(R.string.frame_editor_move_up, R.string.frame_editor_move_up_named, name,
            enabled = z < draft.topZ) { moveLayer(z, 1) })
        row.addView(rowAction(R.string.frame_editor_move_down, R.string.frame_editor_move_down_named, name,
            enabled = z > 0) { moveLayer(z, -1) })
        val visible = draft.isVisible(z)
        row.addView(rowAction(
            if (visible) R.string.frame_editor_hide else R.string.frame_editor_show,
            if (visible) R.string.frame_editor_hide_named else R.string.frame_editor_show_named,
            name
        ) { toggleLayerVisibility(z) })
        return row
    }

    private fun layerBadge(text: String): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(this@FrameEditorActivity, R.color.transfer_muted))
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
    }

    private fun rowAction(textRes: Int, descriptionRes: Int, name: String, enabled: Boolean = true,
                          action: () -> Unit): TextView = TextView(this).apply {
        text = getString(textRes)
        contentDescription = getString(descriptionRes, name)
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context,
            if (enabled) R.color.transfer_accent else R.color.transfer_muted))
        textSize = 12f
        isEnabled = enabled
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
        setOnClickListener { if (isEnabled) action() }
    }

    private fun rowBackground(selected: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(ContextCompat.getColor(this@FrameEditorActivity, R.color.transfer_surface))
            setStroke(((if (selected) 2 else 1) * density).roundToInt(),
                ContextCompat.getColor(this@FrameEditorActivity,
                    if (selected) R.color.transfer_accent else R.color.transfer_border))
        }
    }

    private fun layerName(index: Int?): String {
        if (index == null) return getString(R.string.frame_editor_layer_image)
        val line = draft.labels[index].text.lineSequence().firstOrNull().orEmpty().trim()
        return if (line.isEmpty()) getString(R.string.frame_editor_layer, index + 1)
        else if (line.length > MAX_LAYER_NAME_CHARS) line.take(MAX_LAYER_NAME_CHARS) else line
    }

    private fun rowDescription(name: String, selected: Boolean, visible: Boolean): String = when {
        selected && !visible -> getString(R.string.frame_editor_layer_state_hidden_selected, name)
        selected -> getString(R.string.frame_editor_layer_state_selected, name)
        !visible -> getString(R.string.frame_editor_layer_state_hidden, name)
        else -> name
    }

    private fun refreshLayerName(z: Int, index: Int) {
        layerNames[z]?.text = layerName(index)
    }

    private fun overlayThumbnail(): Bitmap? {
        val reference = draft.overlay ?: return null
        if (thumbnailRef != reference) {
            thumbnailBitmap?.recycle()
            thumbnailBitmap = store.overlayThumbnail(reference)
            thumbnailRef = reference
        }
        return thumbnailBitmap
    }

    // -------------------------------------------------------------------------------------
    // Overlay and font import

    // ponytail: an imported overlay/font stays in the store's asset folder even when the
    // edit is discarded; the shared store contract has no delete call.
    private fun importOverlay(uri: Uri) {
        if (busy) return
        setBusy(true, R.string.frame_editor_status_importing_frame)
        background {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { store.importOverlay(it) }
                    ?: throw IOException("Cannot open the selected image")
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { overlay ->
                    recordEdit()
                    draft = draft.copy(overlay = overlay).withClampedOrder()
                    thumbnailRef = null
                    selectedZ = draft.overlayZ
                    bindSelection()
                    requestPreview()
                    updateStatus()
                }.onFailure { showError(getString(R.string.frame_editor_import_failed)) }
            }
        }
    }

    private fun importFont(index: Int, uri: Uri) {
        if (index !in draft.labels.indices) return
        setBusy(true, R.string.frame_editor_status_importing_font)
        background {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { store.importFont(it) }
                    ?: throw IOException("Cannot open the selected font")
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { font ->
                    if (index in draft.labels.indices) {
                        recordEdit()
                        updateLabel(index) { it.copy(font = font) }
                        if (index == selectedLabelIndex()) bindSelection()
                    }
                }.onFailure { showError(getString(R.string.frame_editor_font_failed)) }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Save and disable

    private fun saveFrame(message: String) {
        if (busy) return
        val design = draft.withoutBlankLabels()
        if (!hasContent(design)) return
        // Let the preview drain finish so the save is not queued behind constant dragging.
        pendingPreview.set(null)

        setBusy(true, R.string.frame_editor_status_saving)
        background {
            val result = runCatching { store.save(design) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess {
                    savedDesign = design
                    draft = design
                    loadedRevisionId = null
                    bindSelection()
                    requestPreview()
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }.onFailure { showError(getString(R.string.frame_editor_save_failed)) }
            }
        }
    }

    private fun confirmDisable() {
        if (savedDesign == null || busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.frame_editor_disable_title)
            .setMessage(R.string.frame_editor_disable_message)
            .setPositiveButton(R.string.frame_editor_disable) { _, _ -> disableFrame() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun disableFrame() {
        if (busy) return
        pendingPreview.set(null)
        setBusy(true, R.string.frame_editor_status_saving)
        background {
            val result = runCatching { store.disable() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess {
                    savedDesign = null
                    draft = FrameDesign()
                    loadedRevisionId = null
                    undoStack.clear()
                    redoStack.clear()
                    selectedZ = -1
                    thumbnailRef = null
                    clearPreview()
                    bindSelection()
                    Toast.makeText(this, R.string.frame_editor_turned_off, Toast.LENGTH_SHORT).show()
                }.onFailure { showError(getString(R.string.frame_editor_disable_failed)) }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // History

    private fun showHistory() {
        setBusy(true, R.string.frame_editor_status_loading_revision)
        background {
            val result = runCatching { store.revisions() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { revisions ->
                    val dialog = AlertDialog.Builder(this).setTitle(R.string.frame_editor_history)
                        .setNegativeButton(android.R.string.cancel, null)
                    if (revisions.isEmpty()) {
                        dialog.setMessage(R.string.frame_editor_history_empty)
                    } else {
                        val entries = revisions.map { revision ->
                            getString(R.string.frame_editor_revision_row, revision.title, formatDate(revision.createdAt))
                        }.toTypedArray()
                        dialog.setItems(entries) { _, index -> loadRevision(revisions[index].id) }
                    }
                    dialog.show()
                }.onFailure { showError(getString(R.string.frame_editor_history_failed)) }
            }
        }
    }

    private fun loadRevision(id: String) {
        if (busy) return
        setBusy(true, R.string.frame_editor_status_loading_revision)
        background {
            val result = runCatching { store.loadRevision(id) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { design ->
                    recordEdit()
                    loadedRevisionId = id
                    selectedZ = design.topZ
                    thumbnailRef = null
                    restoreDraft(design)
                    updateStatus()
                }.onFailure { showError(getString(R.string.frame_editor_revision_failed)) }
            }
        }
    }

    private fun formatDate(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    // -------------------------------------------------------------------------------------
    // Preview rendering

    private fun requestPreview() {
        if (destroyed) return
        if (!hasContent(draft)) {
            pendingPreview.set(null)
            clearPreview()
            return
        }
        pendingPreview.set(draft)
        startPreviewDrain()
    }

    private fun startPreviewDrain() {
        if (!previewRunning.compareAndSet(false, true)) return
        background { drainPreviews() }
    }

    /** Renders only the newest pending design, so dragging or typing never builds a backlog. */
    private fun drainPreviews() {
        try {
            while (true) {
                val design = pendingPreview.getAndSet(null) ?: break
                val bitmap = runCatching { store.renderPreview(design) }.getOrNull()
                runOnUiThread {
                    if (destroyed) {
                        if (bitmap !== previewBitmap) bitmap?.recycle()
                    } else {
                        showPreview(design, bitmap)
                    }
                }
            }
        } finally {
            previewRunning.set(false)
            if (pendingPreview.get() != null) startPreviewDrain()
        }
    }

    private fun showPreview(design: FrameDesign, bitmap: Bitmap?) {
        // A newer edit already arrived: this bitmap is stale.
        if (bitmap == null || design != draft) {
            if (bitmap !== previewBitmap) bitmap?.recycle()
            return
        }
        val previous = previewBitmap
        previewBitmap = bitmap
        binding.previewImage.setImageBitmap(bitmap)
        if (previous !== bitmap) previous?.recycle()
        updateSelectionBox()
    }

    private fun clearPreview() {
        binding.previewImage.setImageBitmap(null)
        previewBitmap?.recycle()
        previewBitmap = null
        binding.selectionOverlay.showSelection(null)
    }

    private fun updateSelectionBox() {
        val bitmap = previewBitmap
        val frame = bitmap?.let(::imageRect)
        val bounds = if (bitmap != null && frame != null && selectedZ in 0 until draft.layerCount &&
            draft.isVisible(selectedZ)) {
            store.layerBounds(draft, selectedZ, bitmap.width, bitmap.height)
        } else {
            null
        }
        if (bitmap == null || frame == null || bounds == null) {
            binding.selectionOverlay.showSelection(null)
            return
        }
        val scaleX = frame.width() / bitmap.width
        val scaleY = frame.height() / bitmap.height
        val padding = dp(3f).toFloat()
        binding.selectionOverlay.showSelection(RectF(
            frame.left + bounds.left * scaleX - padding,
            frame.top + bounds.top * scaleY - padding,
            frame.left + bounds.right * scaleX + padding,
            frame.top + bounds.bottom * scaleY + padding
        ))
    }

    // -------------------------------------------------------------------------------------
    // Drag a layer on the live preview

    private fun handlePreviewTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingZ = -1
                return startDrag(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingZ < 0) return false
                moveDrag(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = draggingZ >= 0
                draggingZ = -1
                if (wasDragging) {
                    binding.previewImage.parent.requestDisallowInterceptTouchEvent(false)
                    draggingMoved = false
                    bindSelection()
                    updateStatus()
                }
                return wasDragging
            }
        }
        return false
    }

    private fun startDrag(x: Float, y: Float): Boolean {
        if (busy) return false
        val bitmap = previewBitmap ?: return false
        val rect = imageRect(bitmap) ?: return false
        if (!rect.contains(x, y)) return false
        val z = hitTest((x - rect.left) / rect.width() * bitmap.width,
            (y - rect.top) / rect.height() * bitmap.height, bitmap)
        if (z < 0) return false
        draggingZ = z
        draggingMoved = false
        binding.previewImage.parent.requestDisallowInterceptTouchEvent(true)
        if (selectedZ != z) selectLayer(z)
        return true
    }

    /** Topmost visible layer under the touch, else the nearest text centre. */
    private fun hitTest(x: Float, y: Float, bitmap: Bitmap): Int {
        val padding = dp(6f).toFloat()
        for (z in draft.topZ downTo 0) {
            if (!draft.isVisible(z)) continue
            val bounds = store.layerBounds(draft, z, bitmap.width, bitmap.height) ?: continue
            if (x >= bounds.left - padding && x <= bounds.right + padding &&
                y >= bounds.top - padding && y <= bounds.bottom + padding) return z
        }
        var best = -1
        var bestDistance = dp(40f).toFloat()
        draft.labels.forEachIndexed { index, label ->
            if (!label.visible) return@forEachIndexed
            val dx = label.x * bitmap.width - x
            val dy = label.y * bitmap.height - y
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = draft.zForLabel(index)
            }
        }
        return best
    }

    private fun moveDrag(x: Float, y: Float) {
        val z = draggingZ
        if (z !in 0 until draft.layerCount) return
        val bitmap = previewBitmap ?: return
        val rect = imageRect(bitmap) ?: return
        if (!draggingMoved) {
            recordEdit()
            draggingMoved = true
        }
        val nx = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val ny = ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
        draft = if (draft.isImageLayer(z)) {
            draft.copy(imageLayer = draft.imageLayer.copy(x = nx, y = ny))
        } else {
            draft.labelIndexAt(z)?.let { index ->
                draft.copy(labels = draft.labels.toMutableList().apply {
                    set(index, get(index).copy(x = nx, y = ny))
                })
            } ?: draft
        }
        requestPreview()
        withoutFieldEvents {
            val xPercent = (nx * 100).roundToInt()
            binding.xSeek.progress = xPercent
            binding.xValue.text = getString(R.string.frame_editor_percent, xPercent)
            val yPercent = (ny * 100).roundToInt()
            binding.ySeek.progress = yPercent
            binding.yValue.text = getString(R.string.frame_editor_percent, yPercent)
        }
        updateSelectionBox()
    }

    /** Bitmap display rect inside the fit-center preview ImageView. */
    private fun imageRect(bitmap: Bitmap): RectF? {
        val viewWidth = binding.previewImage.width
        val viewHeight = binding.previewImage.height
        if (viewWidth == 0 || viewHeight == 0) return null
        val scale = min(viewWidth.toFloat() / bitmap.width, viewHeight.toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    // -------------------------------------------------------------------------------------
    // Navigation, state helpers

    private fun leaveEditor() {
        if (!isDirty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.frame_editor_discard_title)
            .setMessage(R.string.frame_editor_discard_message)
            .setPositiveButton(R.string.frame_editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.frame_editor_keep_editing, null)
            .show()
    }

    private fun isDirty(): Boolean = savedDesign?.let { it != draft } ?: hasContent(draft)

    private fun hasContent(design: FrameDesign): Boolean =
        design.overlay != null || design.labels.any { it.text.isNotBlank() }

    private fun background(block: () -> Unit) {
        if (destroyed) return
        runCatching { worker.execute(block) }
    }

    private inline fun withoutFieldEvents(block: () -> Unit) {
        val previous = bindingFields
        bindingFields = true
        try {
            block()
        } finally {
            bindingFields = previous
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun designToBundle(design: FrameDesign): Bundle {
        val labels = ArrayList<Bundle>(design.labels.size)
        design.labels.forEach { label ->
            labels.add(Bundle().apply {
                putString(KEY_TEXT, label.text)
                putFloat(KEY_X, label.x)
                putFloat(KEY_Y, label.y)
                putFloat(KEY_SIZE, label.size)
                putInt(KEY_COLOR, label.color)
                putString(KEY_FONT, label.font)
                putBoolean(KEY_VISIBLE, label.visible)
            })
        }
        return Bundle().apply {
            putString(KEY_OVERLAY, design.overlay)
            putString(KEY_CANVAS, design.canvas.name)
            putParcelableArrayList(KEY_LABELS, labels)
            putBundle(KEY_IMAGE_LAYER, Bundle().apply {
                putFloat(KEY_X, design.imageLayer.x)
                putFloat(KEY_Y, design.imageLayer.y)
                putFloat(KEY_SCALE, design.imageLayer.scale)
                putBoolean(KEY_VISIBLE, design.imageLayer.visible)
            })
            putInt(KEY_OVERLAY_Z, design.overlayZ)
            putBundle(KEY_CUSTOM_SIZE, Bundle().apply {
                putInt(KEY_WIDTH, design.customSize.width)
                putInt(KEY_HEIGHT, design.customSize.height)
            })
            design.printSize?.let { size ->
                putBundle(KEY_PRINT_SIZE, Bundle().apply {
                    putDouble(KEY_WIDTH, size.width)
                    putDouble(KEY_HEIGHT, size.height)
                    putString(KEY_UNIT, size.unit.name)
                    putInt(KEY_DPI, size.dpi)
                })
            }
        }
    }

    private fun designFromBundle(bundle: Bundle): FrameDesign {
        val labels = BundleCompat.getParcelableArrayList(bundle, KEY_LABELS, Bundle::class.java)
            ?.map { label ->
                FrameLabel(
                    text = label.getString(KEY_TEXT).orEmpty(),
                    x = label.getFloat(KEY_X, 0.5f),
                    y = label.getFloat(KEY_Y, 0.5f),
                    size = label.getFloat(KEY_SIZE, 0.06f),
                    color = label.getInt(KEY_COLOR, -1),
                    font = label.getString(KEY_FONT),
                    visible = label.getBoolean(KEY_VISIBLE, true)
                )
            }.orEmpty()
        val imageLayer = bundle.getBundle(KEY_IMAGE_LAYER)
        val customSize = bundle.getBundle(KEY_CUSTOM_SIZE)
        return FrameDesign(
            overlay = bundle.getString(KEY_OVERLAY),
            labels = labels,
            canvas = FrameCanvas.entries.firstOrNull { it.name == bundle.getString(KEY_CANVAS) }
                ?: FrameCanvas.MATCH_PNG,
            imageLayer = if (imageLayer == null) FramePlacement() else FramePlacement(
                x = imageLayer.getFloat(KEY_X, 0.5f),
                y = imageLayer.getFloat(KEY_Y, 0.5f),
                scale = imageLayer.getFloat(KEY_SCALE, 1f),
                visible = imageLayer.getBoolean(KEY_VISIBLE, true)
            ),
            overlayZ = bundle.getInt(KEY_OVERLAY_Z, 0),
            customSize = if (customSize == null) FrameSize(1800, 2400) else FrameSize(
                customSize.getInt(KEY_WIDTH, 1800),
                customSize.getInt(KEY_HEIGHT, 2400)
            ),
            printSize = bundle.getBundle(KEY_PRINT_SIZE)?.let { print ->
                val unit = FrameUnit.entries.firstOrNull { it.name == print.getString(KEY_UNIT) }
                unit?.let { FramePrintSize(print.getDouble(KEY_WIDTH), print.getDouble(KEY_HEIGHT), it, print.getInt(KEY_DPI)) }
            }
        ).withClampedOrder()
    }

    private enum class LabelColor(val labelRes: Int, val argb: Int) {
        WHITE(R.string.frame_editor_color_white, -1),
        BLACK(R.string.frame_editor_color_black, 0xFF000000.toInt()),
        GOLD(R.string.frame_editor_color_gold, 0xFFD5A85B.toInt()),
        RED(R.string.frame_editor_color_red, 0xFFE0766B.toInt()),
        TEAL(R.string.frame_editor_color_teal, 0xFF4DB6AC.toInt())
    }

    companion object {
        private const val EXTRA_DIRECTORY = "com.ruangfoto.app.extra.FRAME_EDITOR_DIRECTORY"
        private const val MIN_SIZE_PERCENT = 2
        private const val MAX_SIZE_PERCENT = 25
        private const val MIN_IMAGE_PERCENT = 10
        private const val MAX_IMAGE_PERCENT = 400
        private const val MAX_RATIO = 999
        private const val MIN_CUSTOM_SIDE = 64
        private const val MAX_LABELS = 24
        private const val MAX_UNDO = 50
        private const val MAX_LAYER_NAME_CHARS = 28

        private const val STATE_DRAFT = "frame_editor_draft"
        private const val STATE_SAVED = "frame_editor_saved"
        private const val STATE_SELECTED = "frame_editor_selected"
        private const val STATE_REVISION = "frame_editor_revision"
        private const val STATE_PENDING_FONT = "frame_editor_pending_font"

        private const val KEY_OVERLAY = "overlay"
        private const val KEY_CANVAS = "canvas"
        private const val KEY_LABELS = "labels"
        private const val KEY_TEXT = "text"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_SIZE = "size"
        private const val KEY_COLOR = "color"
        private const val KEY_FONT = "font"
        private const val KEY_VISIBLE = "visible"
        private const val KEY_SCALE = "scale"
        private const val KEY_IMAGE_LAYER = "imageLayer"
        private const val KEY_OVERLAY_Z = "overlayZ"
        private const val KEY_CUSTOM_SIZE = "customSize"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_PRINT_SIZE = "printSize"
        private const val KEY_UNIT = "unit"
        private const val KEY_DPI = "dpi"

        internal fun intent(context: Context, directory: File): Intent =
            Intent(context, FrameEditorActivity::class.java)
                .putExtra(EXTRA_DIRECTORY, directory.absolutePath)
    }
}
