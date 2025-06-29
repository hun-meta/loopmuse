package com.example.loopmuse.data

import android.content.Context
import android.content.SharedPreferences

class PlaybackHistory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("playback_history", Context.MODE_PRIVATE)
    
    fun addToHistory(musicFile: MusicFile) {
        val playedSongs = getPlayedSongs().toMutableSet()
        playedSongs.add(musicFile.id)
        prefs.edit()
            .putStringSet("played_songs", playedSongs)
            .putLong("last_played_${musicFile.id}", System.currentTimeMillis())
            .apply()
    }
    
    fun getPlayedSongs(): Set<String> {
        return prefs.getStringSet("played_songs", emptySet()) ?: emptySet()
    }
    
    fun clearHistory() {
        prefs.edit().clear().apply()
    }
    
    fun isPlayed(musicFile: MusicFile): Boolean {
        return getPlayedSongs().contains(musicFile.id)
    }
    
    fun getUnplayedSongs(allSongs: List<MusicFile>): List<MusicFile> {
        val playedIds = getPlayedSongs()
        return allSongs.filter { !playedIds.contains(it.id) }
    }
}