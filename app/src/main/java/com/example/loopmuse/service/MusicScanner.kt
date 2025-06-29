package com.example.loopmuse.service

import android.content.Context
import android.os.Environment
import com.example.loopmuse.data.MusicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MusicScanner(private val context: Context) {
    
    private val supportedFormats = listOf("mp3", "m4a", "wav", "flac", "ogg")
    
    suspend fun scanMusicFiles(selectedFolders: List<String> = emptyList()): List<MusicFile> = withContext(Dispatchers.IO) {
        val musicFiles = mutableListOf<MusicFile>()
        
        val foldersToScan = if (selectedFolders.isEmpty()) {
            getDefaultMusicDirectories()
        } else {
            selectedFolders.map { File(it) }
        }
        
        foldersToScan.forEach { folder ->
            if (folder.exists() && folder.isDirectory) {
                scanDirectory(folder, musicFiles)
            }
        }
        
        musicFiles
    }
    
    private fun scanDirectory(directory: File, musicFiles: MutableList<MusicFile>) {
        directory.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> scanDirectory(file, musicFiles)
                file.isFile && isSupportedAudioFile(file) -> {
                    musicFiles.add(MusicFile.fromFile(file))
                }
            }
        }
    }
    
    private fun isSupportedAudioFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return supportedFormats.contains(extension)
    }
    
    private fun getDefaultMusicDirectories(): List<File> {
        val directories = mutableListOf<File>()
        
        // External storage music directory
        val externalMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (externalMusicDir.exists()) {
            directories.add(externalMusicDir)
        }
        
        // Downloads directory (common place for music files)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir.exists()) {
            directories.add(downloadsDir)
        }
        
        return directories
    }
    
    fun getAvailableFolders(): List<File> {
        val folders = mutableListOf<File>()
        val rootDirs = getDefaultMusicDirectories()
        
        rootDirs.forEach { rootDir ->
            collectMusicFolders(rootDir, folders)
        }
        
        return folders
    }
    
    private fun collectMusicFolders(directory: File, folders: MutableList<File>) {
        if (!directory.exists() || !directory.isDirectory) return
        
        var hasAudioFiles = false
        val subDirectories = mutableListOf<File>()
        
        directory.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> subDirectories.add(file)
                file.isFile && isSupportedAudioFile(file) -> hasAudioFiles = true
            }
        }
        
        if (hasAudioFiles) {
            folders.add(directory)
        }
        
        subDirectories.forEach { subDir ->
            collectMusicFolders(subDir, folders)
        }
    }
}