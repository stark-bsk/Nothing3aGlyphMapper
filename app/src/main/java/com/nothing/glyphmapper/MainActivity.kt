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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nothing.glyphmapper.ui.theme.NothingBlack
import com.nothing.glyphmapper.ui.theme.NothingCard
import com.nothing.glyphmapper.ui.theme.NothingCardElevated
import com.nothing.glyphmapper.ui.theme.NothingGlyphMapperTheme
import com.nothing.glyphmapper.ui.theme.NothingGreen
import com.nothing.glyphmapper.ui.theme.NothingHairline
import com.nothing.glyphmapper.ui.theme.NothingMuted
import com.nothing.glyphmapper.ui.theme.NothingRed
import com.nothing.glyphmapper.ui.theme.NothingWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?
)

class MainActivity : ComponentActivity() {

    private lateinit var preferences: GlyphPreferences

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission state is reflected via NotificationManagerCompat */ }

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
            NothingGlyphMapperTheme {
                GlyphMapperScreen(preferences = preferences)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    Scaffold(
        containerColor = NothingBlack,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (settings.masterEnabled && hasNotificationAccess) {
                                        NothingRed
                                    } else {
                                        NothingMuted
                                    }
                                )
                        )
                        Text(
                            text = "GLYPH MAPPER // 3A",
                            style = MaterialTheme.typography.titleLarge,
                            color = NothingWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = NothingBlack,
                    titleContentColor = NothingWhite
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(NothingBlack)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
        ) {
            // 1. Interactive Phone (3a) Rear Glyph Visualizer Card
            item(key = "visualizer_card") {
                Phone3aGlyphVisualizerCard(
                    activeStrip = activeTestStrip,
                    isDevice3a = GlyphController.isPhone3a(),
                    onTestStrip = { strip -> triggerStripTest(strip) }
                )
            }

            // 2. Material 3 Status & Controls Card
            item(key = "controls_card") {
                StatusAndControlsCard(
                    hasNotificationAccess = hasNotificationAccess,
                    masterEnabled = settings.masterEnabled,
                    pulseMode = settings.pulseMode,
                    onGrantAccess = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onMasterToggle = { enabled ->
                        scope.launch { preferences.setMasterEnabled(enabled) }
                    },
                    onPulseToggle = { pulse ->
                        scope.launch { preferences.setPulseMode(pulse) }
                    }
                )
            }

            // 3. Sticky Search Bar & Section Header
            stickyHeader(key = "search_header") {
                Surface(
                    color = NothingBlack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "APP MAPPINGS",
                                style = MaterialTheme.typography.labelLarge,
                                color = NothingMuted
                            )
                            Text(
                                text = "${settings.mappings.size} ACTIVE",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (settings.mappings.isNotEmpty()) NothingRed else NothingMuted
                            )
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = "Search ${installedApps.size} installed apps...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NothingMuted
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
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NothingWhite),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = NothingCard,
                                unfocusedContainerColor = NothingCard,
                                focusedBorderColor = NothingRed,
                                unfocusedBorderColor = NothingHairline,
                                focusedTextColor = NothingWhite,
                                unfocusedTextColor = NothingWhite,
                                cursorColor = NothingRed
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 4. App Picker List
            if (isLoadingApps) {
                item(key = "loading_indicator") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = NothingRed)
                    }
                }
            } else {
                items(
                    items = filteredApps,
                    key = { it.packageName }
                ) { app ->
                    val assignedStrips = settings.mappings[app.packageName].orEmpty()
                    AppMappingCard(
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
private fun Phone3aGlyphVisualizerCard(
    activeStrip: GlyphStrip?,
    isDevice3a: Boolean,
    onTestStrip: (GlyphStrip) -> Unit
) {
    val stripAGlow by animateColorAsState(
        targetValue = if (activeStrip == GlyphStrip.STRIP_A) NothingWhite else Color(0xFF333336),
        animationSpec = tween(durationMillis = 180),
        label = "stripA"
    )
    val stripBGlow by animateColorAsState(
        targetValue = if (activeStrip == GlyphStrip.STRIP_B) NothingWhite else Color(0xFF333336),
        animationSpec = tween(durationMillis = 180),
        label = "stripB"
    )
    val stripCGlow by animateColorAsState(
        targetValue = if (activeStrip == GlyphStrip.STRIP_C) NothingWhite else Color(0xFF333336),
        animationSpec = tween(durationMillis = 180),
        label = "stripC"
    )

    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = NothingCard),
        border = BorderStroke(1.dp, NothingHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REAR GLYPH INTERFACE",
                    style = MaterialTheme.typography.titleMedium,
                    color = NothingWhite
                )
                Surface(
                    color = NothingCardElevated,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, NothingHairline)
                ) {
                    Text(
                        text = if (isDevice3a) "24111 // LIVE" else "MODEL 24111",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isDevice3a) NothingRed else NothingMuted,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Minimalist Vector Silhouette of Nothing Phone (3a) Rear Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .size(width = 148.dp, height = 168.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val midX = size.width / 2f
                                val midY = size.height / 2f
                                when {
                                    offset.x < midX -> onTestStrip(GlyphStrip.STRIP_C)
                                    offset.y < midY -> onTestStrip(GlyphStrip.STRIP_A)
                                    else -> onTestStrip(GlyphStrip.STRIP_B)
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height

                    // Phone chassis
                    drawRoundRect(
                        color = Color(0xFF080808),
                        topLeft = Offset.Zero,
                        size = Size(w, h),
                        cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx())
                    )
                    drawRoundRect(
                        color = NothingHairline,
                        topLeft = Offset.Zero,
                        size = Size(w, h),
                        cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                        style = Stroke(width = 1.2.dp.toPx())
                    )

                    // Subtle internal hardware ring / coil outline
                    val ringCenter = Offset(w * 0.5f, h * 0.42f)
                    val ringRadius = w * 0.34f
                    drawCircle(
                        color = Color(0xFF1D1D1F),
                        radius = ringRadius * 0.86f,
                        center = ringCenter,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // Phone (3a) horizontal camera pill island in upper-center
                    val pillW = w * 0.56f
                    val pillH = h * 0.19f
                    val pillLeft = (w - pillW) / 2f
                    val pillTop = ringCenter.y - pillH / 2f
                    drawRoundRect(
                        color = Color(0xFF18181A),
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(pillH / 2f, pillH / 2f)
                    )
                    drawRoundRect(
                        color = Color(0xFF343438),
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(pillH / 2f, pillH / 2f),
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // Three camera lenses inside the pill
                    val lensRadius = pillH * 0.25f
                    val lensY = pillTop + pillH / 2f
                    listOf(0.24f, 0.50f, 0.76f).forEach { frac ->
                        drawCircle(
                            color = Color(0xFF050505),
                            radius = lensRadius,
                            center = Offset(pillLeft + pillW * frac, lensY)
                        )
                        drawCircle(
                            color = Color(0xFF3A3A3E),
                            radius = lensRadius,
                            center = Offset(pillLeft + pillW * frac, lensY),
                            style = Stroke(width = 0.8.dp.toPx())
                        )
                    }

                    // Glyph arc bounding box around the camera island
                    val arcTopLeft = Offset(ringCenter.x - ringRadius, ringCenter.y - ringRadius)
                    val arcSize = Size(ringRadius * 2f, ringRadius * 2f)
                    val coreWidth = 4.5.dp.toPx()
                    val glowWidth = 10.dp.toPx()

                    // Strip C: Left curved arc (indices 0..19)
                    if (activeStrip == GlyphStrip.STRIP_C) {
                        drawArc(
                            color = NothingRed.copy(alpha = 0.45f),
                            startAngle = 128f,
                            sweepAngle = 104f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = glowWidth, cap = StrokeCap.Round)
                        )
                    }
                    drawArc(
                        color = stripCGlow,
                        startAngle = 128f,
                        sweepAngle = 104f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = coreWidth, cap = StrokeCap.Round)
                    )

                    // Strip A: Top-Right arc (indices 20..30)
                    if (activeStrip == GlyphStrip.STRIP_A) {
                        drawArc(
                            color = NothingRed.copy(alpha = 0.45f),
                            startAngle = -52f,
                            sweepAngle = 46f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = glowWidth, cap = StrokeCap.Round)
                        )
                    }
                    drawArc(
                        color = stripAGlow,
                        startAngle = -52f,
                        sweepAngle = 46f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = coreWidth, cap = StrokeCap.Round)
                    )

                    // Strip B: Bottom-Right short arc (indices 31..35)
                    if (activeStrip == GlyphStrip.STRIP_B) {
                        drawArc(
                            color = NothingRed.copy(alpha = 0.45f),
                            startAngle = 14f,
                            sweepAngle = 34f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = glowWidth, cap = StrokeCap.Round)
                        )
                    }
                    drawArc(
                        color = stripBGlow,
                        startAngle = 14f,
                        sweepAngle = 34f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = coreWidth, cap = StrokeCap.Round)
                    )

                    // Bottom Nothing-style red accent dot
                    drawCircle(
                        color = NothingRed,
                        radius = 2.5.dp.toPx(),
                        center = Offset(w * 0.5f, h * 0.87f)
                    )
                }
            }

            // Live Hardware Test Pill Buttons (2s)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlyphStrip.entries.forEach { strip ->
                    val isTesting = activeStrip == strip
                    OutlinedButton(
                        onClick = { onTestStrip(strip) },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isTesting) NothingRed else NothingHairline
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isTesting) NothingRed else NothingCardElevated,
                            contentColor = NothingWhite
                        ),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            text = "TEST ${strip.label}",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusAndControlsCard(
    hasNotificationAccess: Boolean,
    masterEnabled: Boolean,
    pulseMode: Boolean,
    onGrantAccess: () -> Unit,
    onMasterToggle: (Boolean) -> Unit,
    onPulseToggle: (Boolean) -> Unit
) {
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = NothingCard),
        border = BorderStroke(1.dp, NothingHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "SYSTEM // CONTROLS",
                style = MaterialTheme.typography.titleMedium,
                color = NothingWhite
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (hasNotificationAccess) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.NotificationsOff
                        },
                        contentDescription = null,
                        tint = if (hasNotificationAccess) NothingGreen else NothingRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = if (hasNotificationAccess) {
                                "Notification Listener Active"
                            } else {
                                "Notification Access Required"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = NothingWhite
                        )
                        Text(
                            text = if (hasNotificationAccess) {
                                "Monitoring unread notifications"
                            } else {
                                "Enable access to trigger Glyph strips"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = NothingMuted
                        )
                    }
                }

                if (!hasNotificationAccess) {
                    Button(
                        onClick = onGrantAccess,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NothingRed,
                            contentColor = NothingWhite
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "GRANT",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            DottedDivider()

            // Master Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Master Glyph Mapping",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NothingWhite
                    )
                    Text(
                        text = "Illuminate strips for mapped unread apps",
                        style = MaterialTheme.typography.bodySmall,
                        color = NothingMuted
                    )
                }
                Switch(
                    checked = masterEnabled,
                    onCheckedChange = onMasterToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NothingWhite,
                        checkedTrackColor = NothingRed,
                        uncheckedThumbColor = NothingMuted,
                        uncheckedTrackColor = NothingCardElevated,
                        uncheckedBorderColor = NothingHairline
                    )
                )
            }

            DottedDivider()

            // Pulse vs Solid Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (pulseMode) "Breathing Pulse" else "Solid Glow",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NothingWhite
                    )
                    Text(
                        text = if (pulseMode) {
                            "Gently animates active Glyph strips"
                        } else {
                            "Steady illumination while unread"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NothingMuted
                    )
                }
                Switch(
                    checked = pulseMode,
                    onCheckedChange = onPulseToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NothingWhite,
                        checkedTrackColor = NothingRed,
                        uncheckedThumbColor = NothingMuted,
                        uncheckedTrackColor = NothingCardElevated,
                        uncheckedBorderColor = NothingHairline
                    )
                )
            }
        }
    }
}

@Composable
private fun DottedDivider(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        drawLine(
            color = NothingHairline,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
        )
    }
}

@Composable
private fun AppMappingCard(
    app: InstalledApp,
    assignedStrips: Set<GlyphStrip>,
    onToggleStrip: (GlyphStrip) -> Unit
) {
    val isMapped = assignedStrips.isNotEmpty()
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isMapped) NothingCardElevated else NothingCard
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isMapped) NothingRed.copy(alpha = 0.55f) else NothingHairline
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NothingCardElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = NothingWhite
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NothingWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = NothingMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlyphStrip.entries.forEach { strip ->
                    val selected = strip in assignedStrips
                    FilterChip(
                        selected = selected,
                        onClick = { onToggleStrip(strip) },
                        label = {
                            Text(
                                text = strip.label,
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = NothingCard,
                            labelColor = NothingMuted,
                            selectedContainerColor = NothingRed,
                            selectedLabelColor = NothingWhite
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = NothingHairline,
                            selectedBorderColor = NothingRed
                        ),
                        modifier = Modifier.height(34.dp)
                    )
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
