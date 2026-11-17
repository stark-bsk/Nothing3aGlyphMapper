package com.nothing.glyphmapper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val NothingBlack = Color(0xFF000000)
private val NothingSurface = Color(0xFF141414)
private val NothingSurfaceElevated = Color(0xFF1E1E1E)
private val NothingBorder = Color(0xFF2C2C2E)
private val NothingRed = Color(0xFFD71921)
private val NothingWhite = Color(0xFFFFFFFF)
private val NothingMuted = Color(0xFF8E8E93)

private val NothingColorScheme = darkColorScheme(
    primary = NothingRed,
    onPrimary = NothingWhite,
    background = NothingBlack,
    onBackground = NothingWhite,
    surface = NothingSurface,
    onSurface = NothingWhite,
    surfaceVariant = NothingSurfaceElevated,
    onSurfaceVariant = NothingMuted,
    outline = NothingBorder
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?
)

class MainActivity : ComponentActivity() {

    private lateinit var preferences: GlyphPreferences

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled via system state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferences = GlyphPreferences(applicationContext)
        GlyphController.init(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme(colorScheme = NothingColorScheme) {
                GlyphMapperScreen(preferences = preferences)
            }
        }
    }
}

@Composable
fun GlyphMapperScreen(preferences: GlyphPreferences) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val settings by preferences.settingsFlow.collectAsState(initial = GlyphSettings())

    var hasNotificationAccess by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationAccess = NotificationManagerCompat
                    .getEnabledListenerPackages(context)
                    .contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var activeTestStrip by remember { mutableStateOf<GlyphStrip?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        installedApps = loadLaunchableApps(context.packageManager, context.packageName)
        isLoadingApps = false
    }

    val filteredApps = remember(installedApps, searchQuery, settings.mappings) {
        val query = searchQuery.trim()
        val base = if (query.isEmpty()) {
            installedApps
        } else {
            installedApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        // Keep mapped apps easy to spot at the top when not searching
        if (query.isEmpty()) {
            base.sortedWith(
                compareByDescending<InstalledApp> { settings.mappings.containsKey(it.packageName) }
                    .thenBy { it.label.lowercase() }
            )
        } else {
            base
        }
    }

    fun triggerStripTest(strip: GlyphStrip) {
        testJob?.cancel()
        activeTestStrip = strip
        testJob = scope.launch {
            GlyphController.updateActiveStrips(
                context = context,
                stripA = strip == GlyphStrip.STRIP_A,
                stripB = strip == GlyphStrip.STRIP_B,
                stripC = strip == GlyphStrip.STRIP_C,
                pulse = settings.pulseMode
            )
            delay(2000L)
            GlyphController.turnOffAndClose()
            activeTestStrip = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NothingBlack)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "GLYPH MAPPER",
                    color = NothingWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = if (GlyphController.isPhone3a()) {
                        "NOTHING PHONE (3a) // 24111 READY"
                    } else {
                        "TARGET: NOTHING PHONE (3a) [24111]"
                    },
                    color = NothingMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (settings.masterEnabled && hasNotificationAccess) NothingRed else NothingMuted)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Status & Controls Card
        Surface(
            color = NothingSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, NothingBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (hasNotificationAccess) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.NotificationsOff
                            },
                            contentDescription = null,
                            tint = if (hasNotificationAccess) Color(0xFF34C759) else NothingRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasNotificationAccess) {
                                "Notification Access Granted"
                            } else {
                                "Notification Access Required"
                            },
                            color = NothingWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (!hasNotificationAccess) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NothingRed,
                            contentColor = NothingWhite
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "GRANT NOTIFICATION ACCESS",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }

                HorizontalDivider(
                    color = NothingBorder,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // Master Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Master Glyph Mapping",
                            color = NothingWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Light up strips for unread mapped notifications",
                            color = NothingMuted,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.masterEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { preferences.setMasterEnabled(enabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NothingWhite,
                            checkedTrackColor = NothingRed,
                            uncheckedThumbColor = NothingMuted,
                            uncheckedTrackColor = NothingSurfaceElevated
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Pulse vs Solid Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (settings.pulseMode) "Breathing Pulse" else "Solid Glow",
                            color = NothingWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (settings.pulseMode) {
                                "Gently animates active strips"
                            } else {
                                "Steady illumination while unread"
                            },
                            color = NothingMuted,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.pulseMode,
                        onCheckedChange = { pulse ->
                            scope.launch { preferences.setPulseMode(pulse) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NothingWhite,
                            checkedTrackColor = NothingRed,
                            uncheckedThumbColor = NothingMuted,
                            uncheckedTrackColor = NothingSurfaceElevated
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Live Hardware Test Bar
        Text(
            text = "LIVE HARDWARE TEST (2S)",
            color = NothingMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlyphStrip.entries.forEach { strip ->
                val isTesting = activeTestStrip == strip
                OutlinedButton(
                    onClick = { triggerStripTest(strip) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isTesting) NothingRed else NothingBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isTesting) NothingRed.copy(alpha = 0.2f) else NothingSurface,
                        contentColor = if (isTesting) NothingRed else NothingWhite
                    ),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                ) {
                    Text(
                        text = "Test ${strip.label}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    text = "Search ${installedApps.size} installed apps...",
                    color = NothingMuted,
                    fontSize = 14.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = NothingMuted
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = NothingMuted
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = NothingSurface,
                unfocusedContainerColor = NothingSurface,
                focusedBorderColor = NothingRed,
                unfocusedBorderColor = NothingBorder,
                focusedTextColor = NothingWhite,
                unfocusedTextColor = NothingWhite,
                cursorColor = NothingRed
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        // App Picker List
        if (isLoadingApps) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NothingRed)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { it.packageName }
                ) { app ->
                    val assignedStrips = settings.mappings[app.packageName].orEmpty()
                    AppMappingRow(
                        app = app,
                        assignedStrips = assignedStrips,
                        onToggleStrip = { strip ->
                            scope.launch {
                                preferences.toggleStripForApp(app.packageName, strip)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppMappingRow(
    app: InstalledApp,
    assignedStrips: Set<GlyphStrip>,
    onToggleStrip: (GlyphStrip) -> Unit
) {
    val isMapped = assignedStrips.isNotEmpty()
    Surface(
        color = NothingSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isMapped) NothingRed.copy(alpha = 0.5f) else NothingBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(NothingSurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        color = NothingWhite,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    color = NothingWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    color = NothingMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlyphStrip.entries.forEach { strip ->
                    val selected = strip in assignedStrips
                    Surface(
                        color = if (selected) NothingRed else NothingSurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) NothingRed else NothingBorder
                        ),
                        modifier = Modifier
                            .size(width = 34.dp, height = 32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleStrip(strip) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = strip.label,
                                color = if (selected) NothingWhite else NothingMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadLaunchableApps(
    packageManager: PackageManager,
    selfPackage: String
): List<InstalledApp> = withContext(Dispatchers.IO) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    resolveInfos
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == selfPackage) return@mapNotNull null
            val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            val iconBitmap = runCatching {
                drawableToImageBitmap(info.loadIcon(packageManager))
            }.getOrNull()
            InstalledApp(
                packageName = pkg,
                label = label,
                icon = iconBitmap
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap.asImageBitmap()
    }
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
