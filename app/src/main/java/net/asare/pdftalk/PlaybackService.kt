package net.asare.pdftalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class PlaybackService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "pdftalk_playback"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "net.asare.pdftalk.ACTION_PLAY"
        const val ACTION_PAUSE = "net.asare.pdftalk.ACTION_PAUSE"
        const val ACTION_NEXT = "net.asare.pdftalk.ACTION_NEXT"
        const val ACTION_PREV = "net.asare.pdftalk.ACTION_PREV"
        const val ACTION_STOP = "net.asare.pdftalk.ACTION_STOP"

        const val BROADCAST_STATE_CHANGED = "net.asare.pdftalk.STATE_CHANGED"
        const val BROADCAST_RANGE_UPDATE = "net.asare.pdftalk.RANGE_UPDATE"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_CURRENT_INDEX = "current_index"
        const val EXTRA_TOTAL_SECTIONS = "total_sections"
        const val EXTRA_RANGE_START = "range_start"
        const val EXTRA_RANGE_END = "range_end"
        const val EXTRA_TTS_READY = "tts_ready"
    }

    private val binder = PlaybackBinder()
    private var tts: TextToSpeech? = null
    private var mediaSession: MediaSessionCompat? = null

    private var sectionFiles: List<File> = emptyList()
    private var currentSectionIndex = 0
    private var isPlaying = false
    private var isPaused = false
    private var currentText = ""
    private var ttsReady = false

    private var voicesList: List<Voice> = emptyList()
    private var currentVoiceIndex = 0
    private var currentRateProgress = 33
    private var currentPitchProgress = 33

    inner class PlaybackBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resumePlayback()
            ACTION_PAUSE -> pausePlayback()
            ACTION_NEXT -> nextSection()
            ACTION_PREV -> prevSection()
            ACTION_STOP -> stopPlaybackAndService()
        }
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val available = tts?.voices
            if (available != null) {
                val englishVoices = available.filter { voice ->
                    !voice.isNetworkConnectionRequired &&
                    voice.locale.language == "en"
                }

                val selectedVoices = mutableListOf<Voice>()
                val seenLocales = mutableSetOf<String>()
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

                for (voice in englishVoices) {
                    val localeKey = voice.locale.toString()
                    if (!seenLocales.contains(localeKey)) {
                        selectedVoices.add(voice)
                        seenLocales.add(localeKey)
                    }
                }

                voicesList = selectedVoices.sortedBy { it.locale.displayCountry }

                val defaultIndex = voicesList.indexOfFirst { it.locale.country == "US" }
                if (defaultIndex >= 0) {
                    currentVoiceIndex = defaultIndex
                }
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (isPlaying && !isPaused) {
                        if (currentSectionIndex < sectionFiles.size - 1) {
                            currentSectionIndex++
                            broadcastStateChange()
                            playCurrent()
                        } else {
                            stopPlayback()
                        }
                    }
                }

                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String?) {
                    stopPlayback()
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    broadcastRangeUpdate(start, end)
                }
            })

            ttsReady = true
            broadcastStateChange()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PDFTalk Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows playback controls for PDFTalk"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "PDFTalk").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumePlayback()
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onSkipToNext() {
                    nextSection()
                }

                override fun onSkipToPrevious() {
                    prevSection()
                }

                override fun onStop() {
                    stopPlaybackAndService()
                }
            })
            isActive = true
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).apply {
                action = if (isPaused || !isPlaying) ACTION_PLAY else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPaused || !isPlaying) R.drawable.ic_play else R.drawable.ic_pause
        val playPauseTitle = if (isPaused || !isPlaying) "Play" else "Pause"

        val pageText = if (sectionFiles.isNotEmpty()) {
            "Page ${currentSectionIndex + 1} of ${sectionFiles.size}"
        } else {
            "PDFTalk"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDFTalk")
            .setContentText(pageText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_prev, "Previous", prevIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseIntent)
            .addAction(R.drawable.ic_next, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying && !isPaused)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
        updateMediaSessionState()
    }

    private fun updateMediaSessionState() {
        val state = when {
            isPlaying && !isPaused -> PlaybackStateCompat.STATE_PLAYING
            isPaused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build()

        mediaSession?.setPlaybackState(playbackState)

        if (sectionFiles.isNotEmpty()) {
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Page ${currentSectionIndex + 1}")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "PDFTalk")
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (currentSectionIndex + 1).toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, sectionFiles.size.toLong())
                .build()
            mediaSession?.setMetadata(metadata)
        }
    }

    private fun broadcastStateChange() {
        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_IS_PAUSED, isPaused)
            putExtra(EXTRA_CURRENT_INDEX, currentSectionIndex)
            putExtra(EXTRA_TOTAL_SECTIONS, sectionFiles.size)
            putExtra(EXTRA_TTS_READY, ttsReady)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastRangeUpdate(start: Int, end: Int) {
        val intent = Intent(BROADCAST_RANGE_UPDATE).apply {
            putExtra(EXTRA_RANGE_START, start)
            putExtra(EXTRA_RANGE_END, end)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // Public API for MainActivity binding

    fun setSectionFiles(files: List<File>) {
        sectionFiles = files
        broadcastStateChange()
    }

    fun setCurrentSection(index: Int) {
        if (index in 0 until sectionFiles.size) {
            currentSectionIndex = index
            broadcastStateChange()
        }
    }

    fun getCurrentSection(): Int = currentSectionIndex

    fun getTotalSections(): Int = sectionFiles.size

    fun isPlaying(): Boolean = isPlaying

    fun isPaused(): Boolean = isPaused

    fun isTtsReady(): Boolean = ttsReady

    fun getCurrentText(): String = currentText

    fun getVoicesList(): List<Voice> = voicesList

    fun getVoiceDisplayNames(): List<String> = voicesList.map { getHumanReadableVoiceName(it) }

    fun getCurrentVoiceIndex(): Int = currentVoiceIndex

    fun setVoiceIndex(index: Int) {
        currentVoiceIndex = index
        if (isPlaying) applyTtsSettings()
    }

    fun getRateProgress(): Int = currentRateProgress

    fun setRateProgress(progress: Int) {
        currentRateProgress = progress
        if (isPlaying) applyTtsSettings()
    }

    fun getPitchProgress(): Int = currentPitchProgress

    fun setPitchProgress(progress: Int) {
        currentPitchProgress = progress
        if (isPlaying) applyTtsSettings()
    }

    private fun getHumanReadableVoiceName(voice: Voice): String {
        val locale = voice.locale
        val country = locale.displayCountry
        return if (country.isNotEmpty()) "English ($country)" else "English"
    }

    fun startPlayback() {
        if (sectionFiles.isEmpty()) return

        isPlaying = true
        isPaused = false
        applyTtsSettings()
        playCurrent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        broadcastStateChange()
    }

    fun resumePlayback() {
        if (sectionFiles.isEmpty()) return

        if (!isPlaying) {
            startPlayback()
        } else if (isPaused) {
            isPaused = false
            playCurrent()
            updateNotification()
            broadcastStateChange()
        }
    }

    fun pausePlayback() {
        if (!isPlaying) return

        isPaused = true
        tts?.stop()
        updateNotification()
        broadcastStateChange()
    }

    fun togglePlayPause() {
        if (!isPlaying) {
            startPlayback()
        } else if (isPaused) {
            resumePlayback()
        } else {
            pausePlayback()
        }
    }

    fun stopPlayback() {
        tts?.stop()
        isPlaying = false
        isPaused = false
        updateNotification()
        broadcastStateChange()
    }

    private fun stopPlaybackAndService() {
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun nextSection() {
        if (sectionFiles.isEmpty()) return
        val wasPlaying = isPlaying && !isPaused
        tts?.stop()

        if (currentSectionIndex < sectionFiles.size - 1) {
            currentSectionIndex++
        }

        broadcastStateChange()
        updateNotification()

        if (wasPlaying) {
            playCurrent()
        }
    }

    fun prevSection() {
        if (sectionFiles.isEmpty()) return
        val wasPlaying = isPlaying && !isPaused
        tts?.stop()

        if (currentSectionIndex > 0) {
            currentSectionIndex--
        }

        broadcastStateChange()
        updateNotification()

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
                broadcastStateChange()
                playCurrent()
            }
            return
        }

        tts?.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, "section_$currentSectionIndex")
        updateNotification()
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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        mediaSession?.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopPlaybackAndService()
    }
}
