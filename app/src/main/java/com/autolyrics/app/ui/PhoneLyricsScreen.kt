package com.autolyrics.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autolyrics.app.R
import com.autolyrics.app.service.PlaybackSyncEngine
import com.autolyrics.app.service.SpotifySessionListener
import com.autolyrics.app.ui.theme.BackgroundDark
import com.autolyrics.app.ui.theme.LyricActiveWhite
import com.autolyrics.app.ui.theme.LyricMutedGray
import com.autolyrics.app.ui.theme.SpotifyGreen
import com.autolyrics.app.ui.theme.SurfaceDark

private data class CarDiagnostics(
    val serviceResolved: Boolean,
    val installerPackage: String?,
    val isPlayStoreInstaller: Boolean
)

@Composable
fun PhoneLyricsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by PlaybackSyncEngine.state.collectAsState()
    val lazyListState = rememberLazyListState()

    var hasNotificationPermission by remember {
        mutableStateOf(isNotificationServiceEnabled(context))
    }
    var diagnostics by remember {
        mutableStateOf(runCarDiagnostics(context))
    }
    var showAndroidAutoHelp by remember {
        mutableStateOf(!diagnostics.isPlayStoreInstaller)
    }
    var showSpotifyConfigDialog by remember {
        mutableStateOf(false)
    }
    var showSafetyDisclaimerDialog by remember {
        mutableStateOf(true)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                showSafetyDisclaimerDialog = true
                val enabled = isNotificationServiceEnabled(context)
                hasNotificationPermission = enabled
                diagnostics = runCarDiagnostics(context)
                if (enabled) {
                    try {
                        NotificationListenerService.requestRebind(
                            ComponentName(context, SpotifySessionListener::class.java)
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(state.activeIndex) {
        if (state.activeIndex in state.lines.indices) {
            lazyListState.animateScrollToItem(
                index = state.activeIndex,
                scrollOffset = -200
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundDark)
        ) {
            // Persistent Header
            HeaderSection(
                trackTitle = state.trackTitle,
                artistName = state.artistName,
                isPlaying = state.isPlaying,
                source = state.source,
                statusMessage = state.statusMessage,
                onToggleHelp = { showAndroidAutoHelp = !showAndroidAutoHelp },
                onOpenSettings = { showSpotifyConfigDialog = true },
                onRefresh = { PlaybackSyncEngine.retryFetch() }
            )

            // Safety Warning Notice Strip
            SafetyNoticePill(
                onClick = { showSafetyDisclaimerDialog = true }
            )

            // Notification Permission Banner if not granted
            if (!hasNotificationPermission) {
                PermissionBanner(
                    onOpenSettings = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                )
            }

            if (showAndroidAutoHelp) {
                AndroidAutoSetupBanner(
                    diagnostics = diagnostics,
                    onOpenAndroidAutoSettings = {
                        openAndroidAutoSettings(context)
                    },
                    onOpenAndroidAutoAppInfo = {
                        openAndroidAutoAppInfo(context)
                    },
                    onDismiss = { showAndroidAutoHelp = false }
                )
            }

            HorizontalDivider(
                color = Color(0xFF282828),
                thickness = 1.dp
            )

            // Main Lyrics Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when {
                    state.isLoading -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = SpotifyGreen,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Searching lyrics across sources...",
                                color = LyricMutedGray,
                                fontSize = 15.sp
                            )
                        }
                    }

                    state.lines.isEmpty() -> {
                        val emptyText = when {
                            state.trackTitle.isEmpty() ->
                                "Play a song on Spotify to start live synced lyrics"
                            !state.statusMessage.isNullOrBlank() ->
                                state.statusMessage.orEmpty()
                            else ->
                                "No synced lyrics found for this track"
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Text(
                                text = emptyText,
                                color = LyricMutedGray,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (state.trackTitle.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { PlaybackSyncEngine.retryFetch() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF2A2A2A),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_refresh),
                                            contentDescription = "Retry",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Retry Search", fontSize = 13.sp)
                                    }

                                    Button(
                                        onClick = { showSpotifyConfigDialog = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SpotifyGreen,
                                            contentColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Text("Spotify Direct API", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = 24.dp,
                                vertical = 120.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            itemsIndexed(
                                items = state.lines,
                                key = { index, line -> "${index}_${line.timeMs}" }
                            ) { index, line ->
                                val isActive = index == state.activeIndex
                                val scale by animateFloatAsState(
                                    targetValue = if (isActive) 1.05f else 1.0f,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "lyricScale"
                                )

                                Text(
                                    text = line.text,
                                    color = if (isActive) LyricActiveWhite else LyricMutedGray,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = if (isActive) 24.sp else 20.sp,
                                    lineHeight = 32.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                        )
                                        .padding(vertical = 4.dp, horizontal = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                color = Color(0xFF282828),
                thickness = 1.dp
            )

            // Playback Controls Bar: Previous | Next | Play/Pause | -30s | +30s
            PlaybackControlsBar(
                isPlaying = state.isPlaying,
                onPrevious = { PlaybackSyncEngine.skipToPrevious() },
                onNext = { PlaybackSyncEngine.skipToNext() },
                onTogglePlayPause = { PlaybackSyncEngine.togglePlayPause() },
                onSeekBackward30 = { PlaybackSyncEngine.seekBackward30s() },
                onSeekForward30 = { PlaybackSyncEngine.seekForward30s() }
            )
        }
    }

    if (showSpotifyConfigDialog) {
        SpotifyDirectDialog(
            onDismiss = { showSpotifyConfigDialog = false },
            onSaveCookie = { cookie ->
                PlaybackSyncEngine.getLyricsRepository().saveSpDcCookie(cookie)
                PlaybackSyncEngine.retryFetch()
                showSpotifyConfigDialog = false
            },
            onClearCookie = {
                PlaybackSyncEngine.getLyricsRepository().clearSpDcCookie()
                PlaybackSyncEngine.retryFetch()
                showSpotifyConfigDialog = false
            }
        )
    }

    if (showSafetyDisclaimerDialog) {
        SafetyWarningDialog(
            onDismiss = { showSafetyDisclaimerDialog = false }
        )
    }
}

@Composable
private fun HeaderSection(
    trackTitle: String,
    artistName: String,
    isPlaying: Boolean,
    source: String?,
    statusMessage: String?,
    onToggleHelp: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher),
            contentDescription = "DashLyrics Steering Wheel Wave Logo",
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable { onToggleHelp() }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = trackTitle.ifEmpty { "Waiting for Spotify..." },
                color = LyricActiveWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = artistName.ifEmpty { "DashLyrics for Android Auto (Tap icon for setup)" },
                color = Color(0xFFB3B3B3),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (source != null || !statusMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    source?.let { src ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF223826))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = src,
                                color = SpotifyGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (!statusMessage.isNullOrBlank()) {
                        Text(
                            text = statusMessage,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaybackStatusPill(isPlaying = isPlaying)

            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_refresh),
                    contentDescription = "Refresh Lyrics",
                    tint = Color(0xFFB3B3B3),
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_settings),
                    contentDescription = "Settings",
                    tint = Color(0xFFB3B3B3),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PlaybackStatusPill(isPlaying: Boolean) {
    val pillBackground = if (isPlaying) Color(0xFF1DB954).copy(alpha = 0.18f) else Color(0xFF333333)
    val dotColor = if (isPlaying) SpotifyGreen else Color(0xFF888888)
    val label = if (isPlaying) "PLAYING" else "PAUSED"

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(pillBackground)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            color = if (isPlaying) SpotifyGreen else Color(0xFFB3B3B3),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SpotifyDirectDialog(
    onDismiss: () -> Unit,
    onSaveCookie: (String) -> Unit,
    onClearCookie: () -> Unit
) {
    var cookieInput by remember {
        mutableStateOf(PlaybackSyncEngine.getLyricsRepository().getSpDcCookie() ?: "")
    }
    val hasCookie = PlaybackSyncEngine.getLyricsRepository().hasSpotifyAuth()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = LyricActiveWhite,
        textContentColor = Color(0xFFCCCCCC),
        title = {
            Text("Spotify Direct Lyrics API", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column {
                Text(
                    text = if (hasCookie) {
                        "Status: CONNECTED to Spotify Official Lyrics"
                    } else {
                        "Status: Cascading Fallback (LRCLIB + Lyrics.ovh)"
                    },
                    color = if (hasCookie) SpotifyGreen else Color(0xFFFFB74D),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "For 100% lyrics coverage matching the official Spotify app, paste your 'sp_dc' cookie from spotify.com:\n" +
                        "1. Open open.spotify.com on desktop and log in\n" +
                        "2. Press F12 -> Application tab -> Cookies -> open.spotify.com\n" +
                        "3. Copy the 'sp_dc' cookie value and paste below:",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFFAAAAAA)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = { Text("sp_dc Cookie Value") },
                    placeholder = { Text("AQ... or sp_dc token") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SpotifyGreen,
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveCookie(cookieInput.trim()) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyGreen,
                    contentColor = Color.Black
                )
            ) {
                Text("Save & Apply", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (hasCookie) {
                    OutlinedButton(
                        onClick = onClearCookie,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF6B6B)
                        )
                    ) {
                        Text("Clear Cookie")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFFB3B3B3))
                }
            }
        }
    )
}

@Composable
private fun SafetyNoticePill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF261910))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "⚠️ Passenger Use Only • Do not read while driving (Tap for info)",
            color = Color(0xFFFFB74D),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SafetyWarningDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        icon = {
            Text("⚠️", fontSize = 34.sp)
        },
        title = {
            Text(
                text = "Driver Safety Warning",
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = Color(0xFFFFB74D),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Do not read lyrics or interact with this app while driving.\n\n" +
                        "Distracted driving causes severe accidents and is against the law.\n\n" +
                        "This app is intended strictly for passengers or when your vehicle is parked safely.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color(0xFFE0E0E0),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2C221E))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Passenger & Parked Use Only",
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyGreen,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "I Understand • I am a Passenger / Parked",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    )
}

@Composable
private fun PermissionBanner(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2C221E))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = "Notification Access Required",
                color = Color(0xFFFFB74D),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Grant notification listener access so DashLyrics can detect active Spotify sessions.",
                color = Color(0xFFE0E0E0),
                fontSize = 12.sp,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(
                containerColor = SpotifyGreen,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                text = "Grant",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AndroidAutoSetupBanner(
    diagnostics: CarDiagnostics,
    onOpenAndroidAutoSettings: () -> Unit,
    onOpenAndroidAutoAppInfo: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2820))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Android Auto Self-Test & Setup",
                color = SpotifyGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = "Hide",
                color = Color(0xFFB3B3B3),
                fontSize = 12.sp,
                modifier = Modifier.clickable { onDismiss() }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        val serviceLine = if (diagnostics.serviceResolved) {
            "• CarAppService (category.IOT): PASS (Exported & Resolved)"
        } else {
            "• CarAppService (category.IOT): FAIL (Not resolved)"
        }

        val installerLine = if (diagnostics.isPlayStoreInstaller) {
            "• Installer Source: PASS (com.android.vending)"
        } else {
            "• Installer Source: ${diagnostics.installerPackage ?: "sideloaded/ADB"} (Needs -i com.android.vending)"
        }

        Text(
            text = "$serviceLine\n$installerLine\n\n" +
                "Why 'Unknown Sources' alone isn't enough for CarAppService:\n" +
                "1. In Android Auto -> Developer settings, tap 'Application Mode' and set it to 'Developer'.\n" +
                "2. Install with Play Store source: adb install -i com.android.vending -r app-debug.apk\n" +
                "3. Force-Stop Android Auto so it refreshes 'Customize launcher'.",
            color = Color(0xFFE0E0E0),
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpenAndroidAutoSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyGreen,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = "AA Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = onOpenAndroidAutoAppInfo,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF333333),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = "Force-Stop AA",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun runCarDiagnostics(context: Context): CarDiagnostics {
    val pm = context.packageManager
    val carIntent = Intent("androidx.car.app.CarAppService").apply {
        addCategory("androidx.car.app.category.IOT")
        setPackage(context.packageName)
    }
    val resolved = pm.queryIntentServices(carIntent, PackageManager.GET_META_DATA)
    val serviceResolved = resolved.isNotEmpty()

    val installer = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = pm.getInstallSourceInfo(context.packageName)
            info.installingPackageName ?: info.initiatingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    } catch (_: Exception) {
        null
    }

    return CarDiagnostics(
        serviceResolved = serviceResolved,
        installerPackage = installer,
        isPlayStoreInstaller = installer == "com.android.vending"
    )
}

private fun openAndroidAutoSettings(context: Context) {
    val intents = listOf(
        Intent("com.google.android.projection.gearhead.SETTINGS").apply {
            setPackage("com.google.android.projection.gearhead")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        context.packageManager.getLaunchIntentForPackage("com.google.android.projection.gearhead")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:com.google.android.projection.gearhead")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
    for (intent in intents.filterNotNull()) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
}

private fun openAndroidAutoAppInfo(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:com.google.android.projection.gearhead")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    val componentName = ComponentName(context, SpotifySessionListener::class.java).flattenToString()
    return enabledListeners.split(":").any { it.equals(componentName, ignoreCase = true) }
}

@Composable
private fun PlaybackControlsBar(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBackward30: () -> Unit,
    onSeekForward30: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Previous
            PlaybackButton(
                iconRes = R.drawable.ic_skip_previous,
                label = "Previous",
                contentDescription = "Previous Track",
                onClick = onPrevious
            )

            // 2. Next
            PlaybackButton(
                iconRes = R.drawable.ic_skip_next,
                label = "Next",
                contentDescription = "Next Track",
                onClick = onNext
            )

            // 3. Play / Pause (Center highlight)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTogglePlayPause() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(SpotifyGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isPlaying) "Pause" else "Play",
                    color = LyricActiveWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 4. -30s
            PlaybackButton(
                iconRes = R.drawable.ic_replay_30,
                label = "-30s",
                contentDescription = "Seek backward 30 seconds",
                onClick = onSeekBackward30
            )

            // 5. +30s
            PlaybackButton(
                iconRes = R.drawable.ic_forward_30,
                label = "+30s",
                contentDescription = "Seek forward 30 seconds",
                onClick = onSeekForward30
            )
        }
    }
}

@Composable
private fun PlaybackButton(
    iconRes: Int,
    label: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFF242424)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = contentDescription,
                tint = LyricActiveWhite,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = LyricMutedGray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
