package net.asare.pdftalk

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
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

    private var tts: TextToSpeech? = null
    private var voicesList: List<Voice> = emptyList()
    private var voiceDisplayNames: List<String> = emptyList()

    private var sectionFiles: List<File> = emptyList()
    private var currentSectionIndex = 0
    private var isPlaying = false
    private var isPaused = false
    private var currentText = ""

    private var pdfRenderer: PdfRenderer? = null
    private var pdfFileDescriptor: ParcelFileDescriptor? = null
    private var currentPdfUri: Uri? = null
    private var cachedPdfFile: File? = null

    // Settings state (33 progress = 1.0x speed/pitch)
    private var currentRateProgress = 33
    private var currentPitchProgress = 33
    private var currentVoiceIndex = 0

    private val sectionsDir: File
        get() = File(filesDir, "sections")

    private val highlightColor: Int
        get() = ContextCompat.getColor(this, R.color.highlightColor)

    companion object {
        private const val PREFS_NAME = "pdftalk_prefs"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_CURRENT_SECTION = "current_section"
        private const val KEY_HAS_PDF = "has_pdf"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved theme preference (default to dark mode)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, true) // Default to dark
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        setContentView(R.layout.activity_main)

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

        tts = TextToSpeech(this, this)

        pickBtn.setOnClickListener { openPdfPicker() }
        playPauseBtn.setOnClickListener { togglePlayPause() }
        prevBtn.setOnClickListener { prevSection() }
        nextBtn.setOnClickListener { nextSection() }
        settingsBtn.setOnClickListener { showSettingsBottomSheet() }

        // Restore PDF state after theme change
        restorePdfState()

        updateUI()
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

        // Setup voice spinner
        if (voiceDisplayNames.isNotEmpty()) {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceDisplayNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            voiceSpinner.adapter = adapter
            voiceSpinner.setSelection(currentVoiceIndex)

            voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentVoiceIndex = position
                    if (isPlaying) applyTtsSettings()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        // Setup rate seekbar
        rateSeek.progress = currentRateProgress
        updateRatePitchDisplay(rateValue, pitchValue)

        rateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentRateProgress = progress
                updateRatePitchDisplay(rateValue, pitchValue)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isPlaying) applyTtsSettings()
            }
        })

        // Setup pitch seekbar
        pitchSeek.progress = currentPitchProgress
        pitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentPitchProgress = progress
                updateRatePitchDisplay(rateValue, pitchValue)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isPlaying) applyTtsSettings()
            }
        })

        // Setup theme toggle
        val isDarkMode = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
        themeToggle.text = if (isDarkMode) "Switch to Light Mode" else "Switch to Dark Mode"
        themeToggle.setOnClickListener {
            bottomSheet.dismiss()
            toggleTheme()
        }

        bottomSheet.show()
    }

    private fun updateRatePitchDisplay(rateValue: TextView, pitchValue: TextView) {
        val rate = 0.5f + (currentRateProgress / 100f) * 1.5f
        val pitch = 0.5f + (currentPitchProgress / 100f) * 1.5f
        rateValue.text = String.format("%.1fx", rate)
        pitchValue.text = String.format("%.1fx", pitch)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val available = tts?.voices
            if (available != null) {
                // Filter to only English voices, offline, and deduplicate by quality
                val englishVoices = available.filter { voice ->
                    !voice.isNetworkConnectionRequired &&
                    voice.locale.language == "en"
                }

                // Select one good voice per locale variant
                val selectedVoices = mutableListOf<Voice>()
                val seenLocales = mutableSetOf<String>()

                // Prefer voices with these patterns (standard quality)
                val preferredPatterns = listOf("iom", "iob", "iol")

                for (pattern in preferredPatterns) {
                    for (voice in englishVoices) {
                        val localeKey = voice.locale.toString()
                        if (!seenLocales.contains(localeKey) && voice.name.contains(pattern, ignoreCase = true)) {
                            selectedVoices.add(voice)
                            seenLocales.add(localeKey)
                        }
                    }
                }

                // If we didn't find preferred voices, add any English voice per locale
                for (voice in englishVoices) {
                    val localeKey = voice.locale.toString()
                    if (!seenLocales.contains(localeKey)) {
                        selectedVoices.add(voice)
                        seenLocales.add(localeKey)
                    }
                }

                voicesList = selectedVoices.sortedBy { it.locale.displayCountry }
                voiceDisplayNames = voicesList.map { getHumanReadableVoiceName(it) }

                // Set default voice to en-us
                val defaultIndex = voicesList.indexOfFirst { it.locale.country == "US" }
                if (defaultIndex >= 0) {
                    currentVoiceIndex = defaultIndex
                }
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (isPlaying && !isPaused) {
                            if (currentSectionIndex < sectionFiles.size - 1) {
                                currentSectionIndex++
                                updateUI()
                                renderCurrentPage()
                                playCurrent()
                            } else {
                                stopPlayback()
                                statusText.text = "Finished all sections"
                            }
                        }
                    }
                }

                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        statusText.text = "TTS error"
                        stopPlayback()
                    }
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    runOnUiThread {
                        highlightText(start, end)
                    }
                }
            })

            statusText.text = "TTS ready"
        } else {
            statusText.text = "TTS init failed"
        }
    }

    private fun getHumanReadableVoiceName(voice: Voice): String {
        val locale = voice.locale
        val country = locale.displayCountry

        return if (country.isNotEmpty()) {
            "English ($country)"
        } else {
            "English"
        }
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
                                val text = stripper.getText(doc).trim()
                                if (text.isNotEmpty()) {
                                    val file = File(sectionsDir, "section_${String.format("%04d", page)}.txt")
                                    file.writeText(text)
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

        if (isPlaying) {
            if (isPaused) {
                isPaused = false
                playCurrent()
                playPauseBtn.setImageResource(R.drawable.ic_pause)
                statusText.text = "Playing..."
            } else {
                isPaused = true
                tts?.stop()
                playPauseBtn.setImageResource(R.drawable.ic_play)
                statusText.text = "Paused"
            }
        } else {
            isPlaying = true
            isPaused = false
            applyTtsSettings()
            playCurrent()
            playPauseBtn.setImageResource(R.drawable.ic_pause)
            statusText.text = "Playing..."
        }
    }

    private fun stopPlayback() {
        tts?.stop()
        isPlaying = false
        isPaused = false
        playPauseBtn.setImageResource(R.drawable.ic_play)
        statusText.text = if (sectionFiles.isNotEmpty()) "Stopped" else "No PDF loaded"

        // Clear highlighting
        if (currentText.isNotEmpty()) {
            pageTextView.text = currentText
        }
    }

    private fun prevSection() {
        if (sectionFiles.isEmpty()) return
        val wasPlaying = isPlaying && !isPaused
        tts?.stop()

        if (currentSectionIndex > 0) {
            currentSectionIndex--
        }
        updateUI()
        renderCurrentPage()

        if (wasPlaying) {
            playCurrent()
        }
    }

    private fun nextSection() {
        if (sectionFiles.isEmpty()) return
        val wasPlaying = isPlaying && !isPaused
        tts?.stop()

        if (currentSectionIndex < sectionFiles.size - 1) {
            currentSectionIndex++
        }
        updateUI()
        renderCurrentPage()

        if (wasPlaying) {
            playCurrent()
        }
    }

    private fun playCurrent() {
        if (currentSectionIndex >= sectionFiles.size) return

        val file = sectionFiles[currentSectionIndex]
        currentText = file.readText()

        if (currentText.isBlank()) {
            if (currentSectionIndex < sectionFiles.size - 1) {
                currentSectionIndex++
                updateUI()
                renderCurrentPage()
                playCurrent()
            }
            return
        }

        pageTextView.text = currentText
        tts?.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, "section_$currentSectionIndex")
    }

    private fun applyTtsSettings() {
        val rate = 0.5f + (currentRateProgress / 100f) * 1.5f
        val pitch = 0.5f + (currentPitchProgress / 100f) * 1.5f
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)

        voicesList.getOrNull(currentVoiceIndex)?.let { v ->
            try {
                tts?.voice = v
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        tts?.shutdown()
        closePdfRenderer()
        super.onDestroy()
    }
}
