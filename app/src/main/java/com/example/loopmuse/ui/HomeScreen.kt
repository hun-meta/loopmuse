package com.example.loopmuse.ui

import android.Manifest
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.example.loopmuse.data.MusicFile
import com.example.loopmuse.service.MusicScanner
import com.example.loopmuse.service.MusicServiceConnection
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val musicScanner = remember { MusicScanner(context) }
    val musicServiceConnection = remember { MusicServiceConnection(context) }
    
    // Android 13+ (API 33+)에서는 READ_MEDIA_AUDIO 권한 사용
    val storagePermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        rememberPermissionState(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    
    var showFolderSelection by remember { mutableStateOf(false) }
    var selectedFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    
    val isServiceConnected by musicServiceConnection.isConnected.collectAsStateWithLifecycle()
    val isPlaying by musicServiceConnection.isPlaying.collectAsStateWithLifecycle()
    val currentTrack by musicServiceConnection.currentTrack.collectAsStateWithLifecycle()
    val songCounts by musicServiceConnection.songCounts.collectAsStateWithLifecycle()
    
    // Stable derived state to prevent unnecessary recompositions
    val hasPermissions by remember {
        derivedStateOf { storagePermissionState.status.isGranted }
    }
    
    val isReadyToPlay by remember {
        derivedStateOf { hasPermissions && isServiceConnected }
    }
    
    // Stable state for Next button visibility - prevent flicker during transitions
    var lastKnownPlayingState by remember { mutableStateOf(false) }
    var buttonStateTimer by remember { mutableStateOf(0L) }
    
    // Update the timer when isPlaying changes
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            lastKnownPlayingState = true
            buttonStateTimer = System.currentTimeMillis()
        } else if (lastKnownPlayingState && currentTrack != null) {
            // If was playing and we have a track, wait briefly before hiding buttons
            kotlinx.coroutines.delay(500) // Wait 500ms
            if (!isPlaying) { // Check again after delay
                lastKnownPlayingState = false
            }
        } else {
            lastKnownPlayingState = false
        }
    }
    
    val shouldShowNextButton by remember {
        derivedStateOf { 
            lastKnownPlayingState || currentTrack != null 
        }
    }
    
    // Connect to service when component mounts
    LaunchedEffect(Unit) {
        musicServiceConnection.bindService()
    }
    
    // Disconnect from service when component unmounts
    DisposableEffect(Unit) {
        onDispose {
            musicServiceConnection.unbindService()
        }
    }
    
    // 앱 생명주기 감지 - onResume 시에만 서비스에 폴더 설정 적용
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 앱이 포그라운드로 돌아올 때만 실행 (중복 호출 방지)
                    if (isServiceConnected && selectedFolders.isNotEmpty()) {
                        coroutineScope.launch {
                            musicServiceConnection.setSelectedFolders(selectedFolders)
                        }
                    }
                }
                else -> {}
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 서비스 연결 시 즉시 곡 수 로딩 및 스캔 수행
    LaunchedEffect(isServiceConnected) {
        if (isServiceConnected) {
            // 서비스 연결 즉시 스캔하여 곡 수 표시 (폴더 선택 여부와 무관하게)
            musicServiceConnection.setSelectedFolders(selectedFolders)
        }
    }
    
    // 폴더 선택이 변경될 때만 서비스에 적용
    LaunchedEffect(selectedFolders) {
        if (isServiceConnected && selectedFolders.isNotEmpty()) {
            musicServiceConnection.setSelectedFolders(selectedFolders)
        }
    }
    
    if (showFolderSelection) {
        FolderSelectionScreen(
            musicScanner = musicScanner,
            selectedFolders = selectedFolders,
            onFoldersSelected = { folders ->
                selectedFolders = folders
            },
            onBackPressed = { showFolderSelection = false }
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🎵 LoopMuse", fontSize = 28.sp)
                Text("Smart Random Music Player", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                if (!isServiceConnected) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Connecting to music service...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                if (isReadyToPlay) {
                    // Current track display with minimum size to prevent layout shifts
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp), // Minimum height to prevent layout shifts
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            key(currentTrack?.id) {
                                currentTrack?.let { track ->
                                    Text("Now Playing", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = track.title, 
                                        fontSize = 18.sp,
                                        maxLines = 2,
                                        minLines = 1,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = track.folder.substringAfterLast('/'), 
                                        fontSize = 14.sp, 
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("🎵 Playing in background", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                } ?: run {
                                    // Placeholder content when no track is playing
                                    Text("Music Player", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Press Play to start", 
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Ready to play music", 
                                        fontSize = 14.sp, 
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("🎵 Waiting for playback", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Song counts with stable key
                    key(songCounts) {
                        Text(songCounts, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Control buttons with stable layout to prevent flickering
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play/Pause button - always visible
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    if (isPlaying) {
                                        musicServiceConnection.togglePlayPause()
                                    } else {
                                        musicServiceConnection.playRandomUnplayedSong()
                                        // Ensure song counts are refreshed after first play
                                        musicServiceConnection.refreshSongCounts()
                                    }
                                }
                            }
                        ) {
                            Text(if (isPlaying) "⏸️ Pause" else "▶️ Play Random")
                        }
                        
                        // Next button - stable visibility to prevent flicker
                        AnimatedVisibility(
                            visible = shouldShowNextButton,
                            enter = fadeIn(animationSpec = tween(200)),
                            exit = fadeOut(animationSpec = tween(200))
                        ) {
                            Button(
                                onClick = {
                                    musicServiceConnection.playNext()
                                }
                            ) {
                                Text("⏭️ Next")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Folder selection button
                    OutlinedButton(
                        onClick = { showFolderSelection = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📁 Select Music Folders (${selectedFolders.size} selected)")
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Clear history button
                    OutlinedButton(
                        onClick = {
                            musicServiceConnection.clearPlaybackHistory()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔄 Reset Playback History")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Stop service button
                    OutlinedButton(
                        onClick = {
                            musicServiceConnection.stopService()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⏹️ Stop Background Playback")
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // AI recommendation placeholder
                    Button(
                        onClick = { /* TODO: AI 추천 시작 */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🎧 AI 감정 추천 시작하기 (Coming Soon)")
                    }
                    
                    // Background playback info
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text("🎵 Background Playback", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Music continues playing when you exit the app. Use notification controls to manage playback.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                } else {
                    // Permission request UI
                    Text(
                        text = "음악 파일에 접근하기 위해 저장소 권한이 필요합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            "Android 13+ 버전에서는 미디어 파일 접근 권한을 허용해주세요."
                        } else {
                            "저장소 접근 권한을 허용해주세요."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { 
                            storagePermissionState.launchPermissionRequest()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("저장소 권한 허용하기")
                    }
                }
            }
        }
    }
}