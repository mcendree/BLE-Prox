package com.example.pixelproximity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

private val REQUIRED_PERMISSIONS: Array<String>
    get() = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION) // Wiliot SDK requires location for scanning
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

@Composable
fun AppRoot(vm: PixelScanViewModel = viewModel()) {
    val context = LocalContext.current
    val store = remember { CredentialStore(context) }

    var hasCreds by remember { mutableStateOf(store.hasCredentials) }
    if (!hasCreds) {
        CredentialsScreen(
            initialOwner = store.ownerId,
            initialKey = store.apiKey,
            onSave = { owner, key ->
                store.save(owner, key)
                hasCreds = true
            }
        )
        return
    }

    fun hasPerms() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasPerms()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasPerms() }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(REQUIRED_PERMISSIONS) }

    if (!granted) {
        PermissionGate { launcher.launch(REQUIRED_PERMISSIONS) }
        return
    }

    val tracked by vm.trackedId.collectAsStateWithLifecycle()
    if (tracked != null) TrackerScreen(vm) else ScanScreen(vm) { store.clear(); hasCreds = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: PixelScanViewModel, onSignOut: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    var showCalib by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    var showCrash by remember { mutableStateOf(false) }
    var crashText by remember { mutableStateOf<String?>(null) }

    if (showCrash) {
        AlertDialog(
            onDismissRequest = { showCrash = false },
            confirmButton = {
                TextButton(onClick = { showCrash = false }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { CrashLog.clear(ctx); crashText = null; showCrash = false }) {
                    Text("Clear")
                }
            },
            title = { Text("Last crash") },
            text = {
                val t = crashText
                if (t.isNullOrBlank()) {
                    Text("No crash recorded. If the app closed on Scan, reopen it and check here.")
                } else {
                    SelectionContainer {
                        Text(
                            t,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wiliot Pixels") },
                actions = {
                    IconButton(onClick = { crashText = CrashLog.read(ctx); showCrash = true }) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Last crash")
                    }
                    IconButton(onClick = { showCalib = !showCalib }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Calibration")
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Filled.Logout, contentDescription = "Clear credentials")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (scanning) vm.stop() else vm.start() },
                icon = {
                    Icon(
                        if (scanning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                },
                text = { Text(if (scanning) "Stop" else "Scan") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            OutlinedTextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                singleLine = true,
                label = { Text("Search Pixel ID") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                }
            )

            Text(
                text = if (scanning) "Scanning • ${rows.size} pixel(s)"
                else "Stopped • press Scan",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (showCalib) CalibrationCard(calibration) { vm.setCalibration(it) }

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (scanning) "Listening for Wiliot Pixels…\nIDs appear once resolved by the cloud."
                        else "Press Scan to start",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(rows, key = { it.key }) { row ->
                        PixelRowItem(row, calibration) { vm.setTracked(row.pixelId) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun PixelRowItem(row: PixelRow, calib: Calibration, onTrack: () -> Unit) {
    val prox = Proximity.fromMeters(row.meters)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTrack)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(proximityColor(prox))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.pixelId,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (row.resolved) "resolved" else "resolving…",
                style = MaterialTheme.typography.bodySmall,
                color = if (row.resolved) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (row.meters < 0) "—" else "~${formatMeters(row.meters)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${row.rssiRaw} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.MyLocation,
            contentDescription = "Track",
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(vm: PixelScanViewModel) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val tracked by vm.trackedId.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.pixelId == tracked }
    val meters = row?.meters ?: -1.0
    val prox = Proximity.fromMeters(meters)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracking") },
                navigationIcon = {
                    IconButton(onClick = { vm.setTracked(null) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                tracked ?: "",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(40.dp))
            Box(
                Modifier.size(240.dp).clip(RoundedCornerShape(50)).background(proximityColor(prox)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (meters < 0 || row == null) "—" else formatMeters(meters),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        if (row == null) "signal lost" else prox.label,
                        color = Color.Black,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            row?.let {
                Text("Raw RSSI: ${it.rssiRaw} dBm", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Smoothed: ${it.rssiSmoothed.roundToInt()} dBm",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } ?: Text(
                "Waiting to hear from this Pixel again…",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Walk around: the meter grows as you get closer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(
    initialOwner: String,
    initialKey: String,
    onSave: (String, String) -> Unit
) {
    var owner by remember { mutableStateOf(initialOwner) }
    var key by remember { mutableStateOf(initialKey) }

    Scaffold(topBar = { TopAppBar(title = { Text("Wiliot credentials") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp)
        ) {
            Text(
                "Enter your Wiliot owner ID and API key. These are used to resolve " +
                        "encrypted Pixel packets into real Pixel IDs. RSSI and distance are " +
                        "computed on-device and never uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = owner,
                onValueChange = { owner = it },
                label = { Text("Owner ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onSave(owner, key) },
                enabled = owner.isNotBlank() && key.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save & continue") }
            Spacer(Modifier.height(12.dp))
            Text(
                "You can get these from the Wiliot management console (owner/account ID " +
                        "and a mobile/gateway API key).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CalibrationCard(calib: Calibration, onChange: (Calibration) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Calibration (on-device)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text("RSSI at 1 m: ${calib.rssiAt1m} dBm", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = calib.rssiAt1m.toFloat(),
                onValueChange = { onChange(calib.copy(rssiAt1m = it.roundToInt())) },
                valueRange = -90f..-40f
            )
            Text(
                "Path-loss exponent: ${"%.1f".format(calib.pathLoss)}",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = calib.pathLoss.toFloat(),
                onValueChange = { onChange(calib.copy(pathLoss = (it * 10).roundToInt() / 10.0)) },
                valueRange = 1.6f..4.0f
            )
        }
    }
}

@Composable
fun PermissionGate(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                "This app needs Nearby devices (Bluetooth) and Location permissions. " +
                        "The Wiliot SDK requires location to scan for BLE Pixels.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("Grant permissions") }
        }
    }
}

private fun formatMeters(m: Double): String =
    if (m < 1.0) "%.2f m".format(m) else "%.1f m".format(m)

private fun proximityColor(prox: Proximity): Color = when (prox) {
    Proximity.IMMEDIATE -> Color(0xFF4CAF50)
    Proximity.NEAR -> Color(0xFF8BC34A)
    Proximity.MID -> Color(0xFFFFC107)
    Proximity.FAR -> Color(0xFFFF7043)
    Proximity.UNKNOWN -> Color(0xFF9E9E9E)
}
