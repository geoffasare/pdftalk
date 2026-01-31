package net.asare.pdftalk

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var pickBtn: ImageButton
    private lateinit var playPauseBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var statusText: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var pdfPageView: ImageView
    private lateinit var pageTextView: TextView
    private lateinit var textScrollView: ScrollView

    private var playbackService: PlaybackService? = null
    private var serviceBound = false

    private var sectionFiles: List<File> = emptyList()
    private var currentSectionIndex = 0
    private var isPlaying = false
    private var isPaused = false
    private var currentText = ""

    private var pdfRenderer: PdfRenderer? = null
    private var pdfFileDescriptor: ParcelFileDescriptor? = null
    private var currentPdfUri: Uri? = null
    private var cachedPdfFile: File? = null

    private val sectionsDir: File
        get() = File(filesDir, "sections")

    private val highlightColor: Int
        get() = ContextCompat.getColor(this, R.color.highlightColor)

    companion object {
        private const val PREFS_NAME = "pdftalk_prefs"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_CURRENT_SECTION = "current_section"
        private const val KEY_HAS_PDF = "has_pdf"
        private const val NOTIFICATION_PERMISSION_REQUEST = 100
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.PlaybackBinder
            playbackService = binder.getService()
            serviceBound = true

            // Sync service state with activity
            if (sectionFiles.isNotEmpty()) {
                playbackService?.setSectionFiles(sectionFiles)
                playbackService?.setCurrentSection(currentSectionIndex)
            }

            // Get current state from service
            syncStateFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlaybackService.BROADCAST_STATE_CHANGED -> {
                    isPlaying = intent.getBooleanExtra(PlaybackService.EXTRA_IS_PLAYING, false)
                    isPaused = intent.getBooleanExtra(PlaybackService.EXTRA_IS_PAUSED, false)
                    val newIndex = intent.getIntExtra(PlaybackService.EXTRA_CURRENT_INDEX, 0)
                    val ttsReady = intent.getBooleanExtra(PlaybackService.EXTRA_TTS_READY, false)

                    if (newIndex != currentSectionIndex && sectionFiles.isNotEmpty()) {
                        currentSectionIndex = newIndex
                        renderCurrentPage()
                        savePdfState()
                    }

                    updatePlayPauseButton()
                    updateUI()

                    if (ttsReady && !isPlaying) {
                        statusText.text = "TTS ready"
                    } else if (isPlaying && !isPaused) {
                        statusText.text = "Playing..."
                    } else if (isPaused) {
                        statusText.text = "Paused"
                    }
                }
                PlaybackService.BROADCAST_RANGE_UPDATE -> {
                    val start = intent.getIntExtra(PlaybackService.EXTRA_RANGE_START, 0)
                    val end = intent.getIntExtra(PlaybackService.EXTRA_RANGE_END, 0)
                    highlightText(start, end)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved theme preference (default to dark mode)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        setContentView(R.layout.activity_main)

        // Handle system navigation bar insets
        val controlBar = findViewById<View>(R.id.controlBar)
        val originalPaddingBottom = controlBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(controlBar) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                originalPaddingBottom + insets.bottom
            )
            windowInsets
        }

        PDFBoxResourceLoader.init(applicationContext)

        pickBtn = findViewById(R.id.pickBtn)
        playPauseBtn = findViewById(R.id.playPauseBtn)
        prevBtn = findViewById(R.id.prevBtn)
        nextBtn = findViewById(R.id.nextBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        statusText = findViewById(R.id.statusText)
        pageIndicator = findViewById(R.id.pageIndicator)
        pdfPageView = findViewById(R.id.pdfPageView)
        pageTextView = findViewById(R.id.pageTextView)
        textScrollView = findViewById(R.id.textScrollView)

        pickBtn.setOnClickListener { openPdfPicker() }
        playPauseBtn.setOnClickListener { togglePlayPause() }
        prevBtn.setOnClickListener { prevSection() }
        nextBtn.setOnClickListener { nextSection() }
        settingsBtn.setOnClickListener { showSettingsBottomSheet() }

        // Request notification permission on Android 13+
        requestNotificationPermission()

        // Start and bind to service
        val serviceIntent = Intent(this, PlaybackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Restore PDF state after theme change
        restorePdfState()

        updateUI()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(PlaybackService.BROADCAST_STATE_CHANGED)
            addAction(PlaybackService.BROADCAST_RANGE_UPDATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, filter)

        // Sync state when returning to activity
        syncStateFromService()
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
    }

    private fun syncStateFromService() {
        playbackService?.let { service ->
            isPlaying = service.isPlaying()
            isPaused = service.isPaused()

            if (service.getTotalSections() > 0) {
                val serviceIndex = service.getCurrentSection()
                if (serviceIndex != currentSectionIndex && serviceIndex < sectionFiles.size) {
                    currentSectionIndex = serviceIndex
                    renderCurrentPage()
                }
            }

            currentText = service.getCurrentText()
            if (currentText.isNotEmpty()) {
                pageTextView.text = currentText
            }

            updatePlayPauseButton()
            updateUI()

            if (service.isTtsReady() && !isPlaying) {
                statusText.text = if (sectionFiles.isNotEmpty()) "Loaded ${sectionFiles.size} pages" else "TTS ready"
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }

    private fun restorePdfState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hasPdf = prefs.getBoolean(KEY_HAS_PDF, false)

        if (hasPdf) {
            val cacheFile = File(cacheDir, "current.pdf")
            if (cacheFile.exists() && sectionsDir.exists()) {
                cachedPdfFile = cacheFile
                sectionFiles = sectionsDir.listFiles()?.filter { it.extension == "txt" }?.sortedBy { it.name } ?: emptyList()

                if (sectionFiles.isNotEmpty()) {
                    currentSectionIndex = prefs.getInt(KEY_CURRENT_SECTION, 0).coerceIn(0, sectionFiles.size - 1)
                    setupPdfRenderer()
                    renderCurrentPage()
                    statusText.text = "Loaded ${sectionFiles.size} pages"

                    // Sync with service
                    playbackService?.setSectionFiles(sectionFiles)
                    playbackService?.setCurrentSection(currentSectionIndex)
                }
            }
        }
    }

    private fun savePdfState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_HAS_PDF, sectionFiles.isNotEmpty())
            .putInt(KEY_CURRENT_SECTION, currentSectionIndex)
            .apply()
    }

    private fun showSettingsBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)
        bottomSheet.setContentView(view)

        val voiceSpinner = view.findViewById<Spinner>(R.id.sheetVoiceSpinner)
        val rateSeek = view.findViewById<SeekBar>(R.id.sheetRateSeek)
        val pitchSeek = view.findViewById<SeekBar>(R.id.sheetPitchSeek)
        val rateValue = view.findViewById<TextView>(R.id.sheetRateValue)
        val pitchValue = view.findViewById<TextView>(R.id.sheetPitchValue)
        val themeToggle = view.findViewById<Button>(R.id.sheetThemeToggle)

        val service = playbackService
        if (service != null) {
            val voiceDisplayNames = service.getVoiceDisplayNames()
            if (voiceDisplayNames.isNotEmpty()) {
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceDisplayNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                voiceSpinner.adapter = adapter
                voiceSpinner.setSelection(service.getCurrentVoiceIndex())

                voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        service.setVoiceIndex(position)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            rateSeek.progress = service.getRateProgress()
            pitchSeek.progress = service.getPitchProgress()
            updateRatePitchDisplay(rateValue, pitchValue, service.getRateProgress(), service.getPitchProgress())

            rateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateRatePitchDisplay(rateValue, pitchValue, progress, pitchSeek.progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    service.setRateProgress(rateSeek.progress)
                }
            })

            pitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateRatePitchDisplay(rateValue, pitchValue, rateSeek.progress, progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    service.setPitchProgress(pitchSeek.progress)
                }
            })
        }

        // Setup theme toggle
        val isDarkMode = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
        themeToggle.text = if (isDarkMode) "Switch to Light Mode" else "Switch to Dark Mode"
        themeToggle.setOnClickListener {
            bottomSheet.dismiss()
            toggleTheme()
        }

        bottomSheet.show()
    }

    private fun updateRatePitchDisplay(rateValue: TextView, pitchValue: TextView, rateProgress: Int, pitchProgress: Int) {
        val rate = 0.5f + (rateProgress / 100f) * 1.5f
        val pitch = 0.5f + (pitchProgress / 100f) * 1.5f
        rateValue.text = String.format("%.1fx", rate)
        pitchValue.text = String.format("%.1fx", pitch)
    }

    private fun highlightText(start: Int, end: Int) {
        if (currentText.isEmpty() || start < 0 || end > currentText.length) return

        val spannable = SpannableString(currentText)
        spannable.setSpan(
            BackgroundColorSpan(highlightColor),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        pageTextView.text = spannable

        // Auto-scroll to highlighted word
        val layout = pageTextView.layout ?: return
        val line = layout.getLineForOffset(start)
        val y = layout.getLineTop(line)
        textScrollView.smoothScrollTo(0, y)
    }

    private fun openPdfPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                stopPlayback()
                currentPdfUri = uri
                statusText.text = "Loading PDF..."

                Thread {
                    try {
                        // Clear old sections
                        sectionsDir.deleteRecursively()
                        sectionsDir.mkdirs()

                        // Cache PDF file for PdfRenderer
                        val cacheFile = File(cacheDir, "current.pdf")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(cacheFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        cachedPdfFile = cacheFile

                        // Extract text using PDFBox
                        contentResolver.openInputStream(uri).use { input ->
                            val doc = PDDocument.load(input)
                            val totalPages = doc.numberOfPages
                            val files = mutableListOf<File>()

                            for (page in 1..totalPages) {
                                val stripper = PDFTextStripper().apply {
                                    startPage = page
                                    endPage = page
                                }
                                val rawText = stripper.getText(doc).trim()
                                if (rawText.isNotEmpty()) {
                                    val normalizedText = normalizeTextForTts(rawText)
                                    val file = File(sectionsDir, "section_${String.format("%04d", page)}.txt")
                                    file.writeText(normalizedText)
                                    files.add(file)
                                }

                                runOnUiThread {
                                    statusText.text = "Processing page $page of $totalPages..."
                                }
                            }
                            doc.close()
                            sectionFiles = files.sortedBy { it.name }
                        }

                        // Setup PdfRenderer
                        runOnUiThread {
                            setupPdfRenderer()
                        }

                        currentSectionIndex = 0
                        runOnUiThread {
                            statusText.text = "Loaded ${sectionFiles.size} pages"
                            savePdfState()
                            updateUI()
                            renderCurrentPage()

                            // Sync with service
                            playbackService?.setSectionFiles(sectionFiles)
                            playbackService?.setCurrentSection(currentSectionIndex)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread { statusText.text = "Error: ${e.message}" }
                    }
                }.start()
            }
        }
    }

    private fun setupPdfRenderer() {
        closePdfRenderer()
        cachedPdfFile?.let { file ->
            try {
                pdfFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(pdfFileDescriptor!!)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun closePdfRenderer() {
        pdfRenderer?.close()
        pdfFileDescriptor?.close()
        pdfRenderer = null
        pdfFileDescriptor = null
    }

    private fun normalizeTextForTts(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")

        val lines = normalized.split("\n")
        val result = StringBuilder()

        for (i in lines.indices) {
            val currentLine = lines[i].trim()
            val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""

            if (currentLine.isEmpty()) {
                result.append("\n\n")
                continue
            }

            result.append(currentLine)

            val isBulletPoint = currentLine.matches(Regex("^[-•*▪▸►]\\s.*"))
            val isNumberedList = currentLine.matches(Regex("^\\d+[.):]\\s.*"))
            val isLetteredList = currentLine.matches(Regex("^[a-zA-Z][.):]\\s.*"))
            val isListItem = isBulletPoint || isNumberedList || isLetteredList

            val shouldPreserveNewline = when {
                nextLine.isEmpty() -> true
                isListItem -> true
                currentLine.endsWith(".") || currentLine.endsWith("!") ||
                currentLine.endsWith("?") || currentLine.endsWith(":") -> true
                currentLine.length < 50 && !currentLine.endsWith(",") -> true
                nextLine.matches(Regex("^[-•*▪▸►]\\s.*")) -> true
                nextLine.matches(Regex("^\\d+[.):]\\s.*")) -> true
                nextLine.matches(Regex("^[a-zA-Z][.):]\\s.*")) -> true
                currentLine.endsWith("-") -> {
                    result.deleteCharAt(result.length - 1)
                    false
                }
                else -> false
            }

            if (i < lines.size - 1) {
                if (shouldPreserveNewline) {
                    result.append("\n")
                } else {
                    result.append(" ")
                }
            }
        }

        return result.toString()
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun renderCurrentPage() {
        val renderer = pdfRenderer ?: return
        if (currentSectionIndex >= renderer.pageCount) return

        try {
            val page = renderer.openPage(currentSectionIndex)
            val scale = 2f
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt(),
                (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            pdfPageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Also update text view
        if (currentSectionIndex < sectionFiles.size) {
            currentText = sectionFiles[currentSectionIndex].readText()
            pageTextView.text = currentText
        }
    }

    private fun togglePlayPause() {
        if (sectionFiles.isEmpty()) {
            Toast.makeText(this, "No PDF loaded", Toast.LENGTH_SHORT).show()
            return
        }

        playbackService?.togglePlayPause()
    }

    private fun updatePlayPauseButton() {
        if (isPlaying && !isPaused) {
            playPauseBtn.setImageResource(R.drawable.ic_pause)
        } else {
            playPauseBtn.setImageResource(R.drawable.ic_play)
        }
    }

    private fun stopPlayback() {
        playbackService?.stopPlayback()
        isPlaying = false
        isPaused = false
        updatePlayPauseButton()
        statusText.text = if (sectionFiles.isNotEmpty()) "Stopped" else "No PDF loaded"

        // Clear highlighting
        if (currentText.isNotEmpty()) {
            pageTextView.text = currentText
        }
    }

    private fun prevSection() {
        if (sectionFiles.isEmpty()) return

        playbackService?.prevSection()

        // Update locally as well for immediate feedback
        if (currentSectionIndex > 0) {
            currentSectionIndex--
            updateUI()
            renderCurrentPage()
            savePdfState()
        }
    }

    private fun nextSection() {
        if (sectionFiles.isEmpty()) return

        playbackService?.nextSection()

        // Update locally as well for immediate feedback
        if (currentSectionIndex < sectionFiles.size - 1) {
            currentSectionIndex++
            updateUI()
            renderCurrentPage()
            savePdfState()
        }
    }

    private fun updateUI() {
        if (sectionFiles.isEmpty()) {
            pageIndicator.text = "0/0"
            prevBtn.isEnabled = false
            nextBtn.isEnabled = false
            prevBtn.alpha = 0.5f
            nextBtn.alpha = 0.5f
        } else {
            pageIndicator.text = "${currentSectionIndex + 1}/${sectionFiles.size}"
            prevBtn.isEnabled = currentSectionIndex > 0
            nextBtn.isEnabled = currentSectionIndex < sectionFiles.size - 1
            prevBtn.alpha = if (prevBtn.isEnabled) 1.0f else 0.5f
            nextBtn.alpha = if (nextBtn.isEnabled) 1.0f else 0.5f
        }
    }

    private fun toggleTheme() {
        // Save PDF state before theme change causes activity recreation
        savePdfState()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, true)
        val newMode = !isDarkMode

        prefs.edit().putBoolean(KEY_DARK_MODE, newMode).apply()

        AppCompatDelegate.setDefaultNightMode(
            if (newMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        closePdfRenderer()
        super.onDestroy()
    }
}
