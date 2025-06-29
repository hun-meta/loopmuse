package com.example.loopmuse.service

import android.content.Context
import android.media.MediaPlayer
import com.example.loopmuse.data.MusicFile
import com.example.loopmuse.data.PlaybackHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SmartRandomPlayer(private val context: Context) {
    private val playbackHistory = PlaybackHistory(context)
    private val musicScanner = MusicScanner(context)
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: MusicFile? = null
    private var selectedFolders: List<String> = emptyList()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    
    private val _currentTrack = MutableStateFlow<MusicFile?>(null)
    val currentTrack: StateFlow<MusicFile?> = _currentTrack
    
    suspend fun setSelectedFolders(folders: List<String>) {
        selectedFolders = folders
    }
    
    suspend fun playRandomUnplayedSong(): Boolean {
        val allSongs = musicScanner.scanMusicFiles(selectedFolders)
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
    
    suspend fun playNextRandomSong(): Boolean {
        currentSong?.let { song ->
            playbackHistory.addToHistory(song)
        }
        return playRandomUnplayedSong()
    }
    
    private fun playSong(musicFile: MusicFile): Boolean {
        return try {
            stopCurrentSong()
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(musicFile.path)
                prepareAsync()
                setOnPreparedListener { player ->
                    player.start()
                    _isPlaying.value = true
                    currentSong = musicFile
                    _currentTrack.value = musicFile
                }
                setOnCompletionListener {
                    _isPlaying.value = false
                    playbackHistory.addToHistory(musicFile)
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
    
    fun pauseResume() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _isPlaying.value = false
            } else {
                player.start()
                _isPlaying.value = true
            }
        }
    }
    
    fun stopCurrentSong() {
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
    
    fun clearPlaybackHistory() {
        playbackHistory.clearHistory()
    }
    
    suspend fun getUnplayedCount(): Int {
        val allSongs = musicScanner.scanMusicFiles(selectedFolders)
        return playbackHistory.getUnplayedSongs(allSongs).size
    }
    
    suspend fun getTotalSongsCount(): Int {
        return musicScanner.scanMusicFiles(selectedFolders).size
    }
}