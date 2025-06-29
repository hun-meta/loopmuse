package com.example.loopmuse.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.loopmuse.data.MusicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MusicServiceConnection(private val context: Context) {
    
    private var musicService: MusicPlaybackService? = null
    private var isBound = false
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    
    private val _currentTrack = MutableStateFlow<MusicFile?>(null)
    val currentTrack: StateFlow<MusicFile?> = _currentTrack
    
    private val _songCounts = MutableStateFlow("Loading...")
    val songCounts: StateFlow<String> = _songCounts
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlaybackService.LocalBinder
            musicService = binder.getService()
            isBound = true
            _isConnected.value = true
            
            // Start observing service state
            musicService?.let { service ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).apply {
                    launch {
                        service.isPlaying.collect { playing ->
                            _isPlaying.value = playing
                        }
                    }
                    launch {
                        service.currentTrack.collect { track ->
                            _currentTrack.value = track
                        }
                    }
                    launch {
                        service.songCounts.collect { counts ->
                            _songCounts.value = counts
                        }
                    }
                }
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
            _isConnected.value = false
        }
    }
    
    fun bindService() {
        val intent = Intent(context, MusicPlaybackService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        context.startService(intent)
    }
    
    fun unbindService() {
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
            _isConnected.value = false
        }
    }
    
    fun setSelectedFolders(folders: List<String>) {
        musicService?.setSelectedFolders(folders)
    }
    
    fun refreshSongCounts() {
        musicService?.forceUpdateSongCounts()
    }
    
    suspend fun playRandomUnplayedSong(): Boolean {
        return musicService?.playRandomUnplayedSong() ?: false
    }
    
    fun togglePlayPause() {
        val intent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAY_PAUSE
        }
        context.startService(intent)
    }
    
    fun playNext() {
        val intent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_NEXT
        }
        context.startService(intent)
    }
    
    fun stopService() {
        val intent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_STOP
        }
        context.startService(intent)
    }
    
    fun clearPlaybackHistory() {
        musicService?.clearPlaybackHistory()
    }
    
    suspend fun getUnplayedCount(): Int {
        return musicService?.getUnplayedCount() ?: 0
    }
    
    suspend fun getTotalSongsCount(): Int {
        return musicService?.getTotalSongsCount() ?: 0
    }
}