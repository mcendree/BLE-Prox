package com.example.bleproximity

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT
)

@Composable
fun AppRoot(vm: BleScanViewModel = viewModel()) {
    val context = LocalContext.current

    fun hasPerms() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(hasPerms()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.all { it } }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(REQUIRED_PERMISSIONS)
    }

    if (!granted) {
        PermissionGate { launcher.launch(REQUIRED_PERMISSIONS) }
        return
    }

    if (!vm.bluetoothEnabled) {
        BluetoothOffGate()
        return
    }

    val tracked by vm.trackedAddress.collectAsStateWithLifecycle()
    if (tracked != null) {
        TrackerScreen(vm)
    } else {
        ScanScreen(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: BleScanViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    var showCalib by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE Proximity") },
                actions = {
                    IconButton(onClick = { showCalib = !showCalib }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Calibration")
                    }
                    IconButton(onClick = { vm.clear() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear list")
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
                label = { Text("Filter by name or address") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear filter")
                        }
                    }
                }
            )

            Text(
                text = if (scanning) "Scanning • ${devices.size} device(s)"
                else "Stopped • ${devices.size} device(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (showCalib) {
                CalibrationCard(calibration) { vm.setCalibration(it) }
            }

            if (devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (scanning) "Listening for BLE advertisements…"
                        else "Press Scan to start",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(devices, key = { it.address }) { d ->
                        DeviceRow(d, calibration) { vm.setTracked(d.address) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceRow(d: BleDevice, calib: Calibration, onTrack: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val meters = calib.estimateMeters(d.rssiSmoothed)
    val prox = Proximity.fromMeters(meters)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalDot(prox)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.name ?: "(unnamed device)",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    d.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (meters < 0) "—" else "~${formatMeters(meters)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${d.rssiRaw} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onTrack) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Track this device")
            }
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            DetailLine("Proximity", prox.label)
            DetailLine("Smoothed RSSI", "${d.rssiSmoothed.roundToInt()} dBm")
            DetailLine("TX power", d.txPower?.let { "$it dBm" } ?: "not advertised")
            DetailLine(
                "Service UUIDs",
                if (d.serviceUuids.isEmpty()) "none" else d.serviceUuids.joinToString("\n")
            )
            DetailLine("Manufacturer data", d.manufacturerData ?: "none")
        }
    }
}

@Composable
fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SignalDot(prox: Proximity) {
    Box(
        Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(50))
            .background(proximityColor(prox))
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(vm: BleScanViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val tracked by vm.trackedAddress.collectAsStateWithLifecycle()
    val calib by vm.calibration.collectAsStateWithLifecycle()

    // Look up the tracked device in the (unfiltered) store via current list.
    val device = devices.firstOrNull { it.address == tracked }
    val meters = device?.let { calib.estimateMeters(it.rssiSmoothed) } ?: -1.0
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
                device?.name ?: (tracked ?: ""),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                tracked ?: "",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(40.dp))

            Box(
                Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(50))
                    .background(proximityColor(prox)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (meters < 0 || device == null) "—" else formatMeters(meters),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        if (device == null) "signal lost" else prox.label,
                        color = Color.Black,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            device?.let {
                Text("Raw RSSI: ${it.rssiRaw} dBm", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Smoothed: ${it.rssiSmoothed.roundToInt()} dBm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } ?: Text(
                "Waiting to hear from this device again…",
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

@Composable
fun CalibrationCard(calib: Calibration, onChange: (Calibration) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Calibration", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Text("RSSI at 1 m: ${calib.txAt1m} dBm", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = calib.txAt1m.toFloat(),
                onValueChange = { onChange(calib.copy(txAt1m = it.roundToInt())) },
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
            Text(
                "Tip: put the phone 1 m from a device and adjust 'RSSI at 1 m' until the " +
                        "estimate reads ~1 m. Higher path-loss = more attenuation (walls, bodies).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                "This app needs the Nearby devices (Bluetooth) permission to scan for BLE " +
                        "advertisements. It does not use your location.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("Grant permission") }
        }
    }
}

@Composable
fun BluetoothOffGate() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.BluetoothDisabled, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                "Bluetooth is turned off. Enable it from Quick Settings, then reopen the app.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ---- helpers ----

private fun formatMeters(m: Double): String =
    if (m < 1.0) "%.2f m".format(m) else "%.1f m".format(m)

private fun proximityColor(prox: Proximity): Color = when (prox) {
    Proximity.IMMEDIATE -> Color(0xFF4CAF50)
    Proximity.NEAR -> Color(0xFF8BC34A)
    Proximity.MID -> Color(0xFFFFC107)
    Proximity.FAR -> Color(0xFFFF7043)
    Proximity.UNKNOWN -> Color(0xFF9E9E9E)
}
