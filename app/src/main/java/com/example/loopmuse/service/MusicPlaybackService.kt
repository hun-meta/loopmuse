package com.example.loopmuse.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.loopmuse.MainActivity
import com.example.loopmuse.data.MusicFile
import com.example.loopmuse.data.PlaybackHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MusicPlaybackService : Service() {
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "music_playback_channel"
        const val ACTION_PLAY_PAUSE = "action_play_pause"
        const val ACTION_NEXT = "action_next"
        const val ACTION_STOP = "action_stop"
    }
    
    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: MusicFile? = null
    private var selectedFolders: List<String> = emptyList()
    
    // Cache for performance optimization
    private var cachedAllSongs: List<MusicFile> = emptyList()
    private var lastScanTime: Long = 0
    private val scanCacheTimeout = 30000L // 30 seconds
    
    private lateinit var playbackHistory: PlaybackHistory
    private lateinit var musicScanner: MusicScanner
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManagerCompat
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    
    private val _currentTrack = MutableStateFlow<MusicFile?>(null)
    val currentTrack: StateFlow<MusicFile?> = _currentTrack
    
    private val _songCounts = MutableStateFlow("Loading...")
    val songCounts: StateFlow<String> = _songCounts
    
    inner class LocalBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        playbackHistory = PlaybackHistory(this)
        musicScanner = MusicScanner(this)
        notificationManager = NotificationManagerCompat.from(this)
        
        createNotificationChannel()
        initializeMediaSession()
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_STOP -> stopService()
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopCurrentSong()
        mediaSession.release()
        serviceScope.cancel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for music playback"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumePlayback()
                }
                
                override fun onPause() {
                    pausePlayback()
                }
                
                override fun onSkipToNext() {
                    playNext()
                }
                
                override fun onStop() {
                    stopService()
                }
            })
            
            isActive = true
        }
    }
    
    fun getSelectedFolders(): List<String> = selectedFolders
    
    fun setSelectedFolders(folders: List<String>) {
        val foldersChanged = selectedFolders != folders
        val isFirstTimeSet = _songCounts.value == "Loading..."
        selectedFolders = folders
        
        if (foldersChanged || isFirstTimeSet) {
            // 폴더가 변경되거나 최초 설정 시 캐시 무효화 및 곡 수 업데이트
            invalidateCache()
            serviceScope.launch {
                updateSongCounts()
            }
        }
    }
    
    fun forceUpdateSongCounts() {
        serviceScope.launch {
            updateSongCounts()
        }
    }
    
    private fun invalidateCache() {
        cachedAllSongs = emptyList()
        lastScanTime = 0
    }
    
    private suspend fun getCachedOrScanSongs(): List<MusicFile> {
        val currentTime = System.currentTimeMillis()
        
        return if (cachedAllSongs.isNotEmpty() && (currentTime - lastScanTime) < scanCacheTimeout) {
            // Use cached results if available and not expired
            cachedAllSongs
        } else {
            // Scan and cache new results
            withContext(Dispatchers.IO) {
                val songs = musicScanner.scanMusicFiles(selectedFolders)
                cachedAllSongs = songs
                lastScanTime = currentTime
                songs
            }
        }
    }
    
    private suspend fun updateSongCounts() {
        try {
            val total = getTotalSongsCount()
            val unplayed = getUnplayedCount()
            _songCounts.value = "$unplayed unplayed / $total total songs"
        } catch (e: Exception) {
            _songCounts.value = "Error loading song counts"
        }
    }
    
    suspend fun playRandomUnplayedSong(): Boolean {
        val allSongs = getCachedOrScanSongs()
        val unplayedSongs = playbackHistory.getUnplayedSongs(allSongs)
        
        if (unplayedSongs.isEmpty()) {
            if (allSongs.isEmpty()) {
                return false
            }
            playbackHistory.clearHistory()
            return playRandomUnplayedSong()
        }
        
        val randomSong = unplayedSongs.random()
        return playSong(randomSong)
    }
    
    private fun playSong(musicFile: MusicFile): Boolean {
        return try {
            // Clean up previous media player without clearing track info during transitions
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            mediaPlayer = null
            
            // Update track info immediately to maintain UI consistency
            currentSong = musicFile
            _currentTrack.value = musicFile
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(musicFile.path)
                prepareAsync()
                setOnPreparedListener { player ->
                    player.start()
                    _isPlaying.value = true
                    updateMediaSession()
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                setOnCompletionListener {
                    _isPlaying.value = false
                    playbackHistory.addToHistory(musicFile)
                    serviceScope.launch {
                        // 즉시 다음 곡 재생하고, 카운트 업데이트는 백그라운드에서 처리
                        playRandomUnplayedSong()
                        // 카운트 업데이트를 별도 코루틴에서 처리
                        launch {
                            updateSongCounts()
                        }
                    }
                }
                setOnErrorListener { _, _, _ ->
                    _isPlaying.value = false
                    false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                pausePlayback()
            } else {
                resumePlayback()
            }
        }
    }
    
    private fun pausePlayback() {
        mediaPlayer?.pause()
        _isPlaying.value = false
        updateMediaSession()
        updateNotification()
    }
    
    private fun resumePlayback() {
        mediaPlayer?.start()
        _isPlaying.value = true
        updateMediaSession()
        updateNotification()
    }
    
    private fun playNext() {
        serviceScope.launch {
            currentSong?.let { song ->
                playbackHistory.addToHistory(song)
            }
            // 즉시 다음 곡 재생하고, 카운트 업데이트는 백그라운드에서 처리
            playRandomUnplayedSong()
            // 카운트 업데이트를 별도 코루틴에서 처리하여 재생 지연 방지
            launch {
                updateSongCounts()
            }
        }
    }
    
    private fun stopCurrentSong() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
        }
        mediaPlayer = null
        _isPlaying.value = false
        currentSong = null
        _currentTrack.value = null
    }
    
    private fun cleanupMediaPlayerOnly() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
        }
        mediaPlayer = null
    }
    
    private fun stopService() {
        stopCurrentSong()
        stopForeground(true)
        stopSelf()
    }
    
    private fun updateMediaSession() {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (_isPlaying.value) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                0L,
                1f
            )
            .build()
        
        mediaSession.setPlaybackState(playbackState)
        
        currentSong?.let { song ->
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .build()
            
            mediaSession.setMetadata(metadata)
        }
    }
    
    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val playPauseIntent = Intent(this, MusicPlaybackService::class.java).apply {
            action = ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val nextIntent = Intent(this, MusicPlaybackService::class.java).apply {
            action = ACTION_NEXT
        }
        val nextPendingIntent = PendingIntent.getService(
            this, 2, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, MusicPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 3, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val currentTrack = currentSong ?: return createEmptyNotification()
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTrack.title)
            .setContentText(currentTrack.artist)
            .setSubText("LoopMuse")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                null // Previous functionality can be added later
            )
            .addAction(
                if (_isPlaying.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (_isPlaying.value) "Pause" else "Play",
                playPausePendingIntent
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                nextPendingIntent
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
    
    private fun createEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LoopMuse")
            .setContentText("Music Player")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
    
    private fun updateNotification() {
        if (currentSong != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }
    
    fun clearPlaybackHistory() {
        playbackHistory.clearHistory()
        // 이력이 초기화되면 곡 수를 업데이트
        serviceScope.launch {
            updateSongCounts()
        }
    }
    
    suspend fun getUnplayedCount(): Int {
        val allSongs = getCachedOrScanSongs()
        return playbackHistory.getUnplayedSongs(allSongs).size
    }
    
    suspend fun getTotalSongsCount(): Int {
        val allSongs = getCachedOrScanSongs()
        return allSongs.size
    }
}