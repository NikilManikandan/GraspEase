package com.graspease.dev

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.graspease.dev.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import com.graspease.dev.voice.CloudSttConfig
import com.graspease.dev.voice.CloudSttController
import com.graspease.dev.VoiceTesterState
import com.graspease.dev.VoiceTesterViewModel
import java.util.UUID

private val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val TX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private const val CMD_START_REHAB = 0x04

data class BleDevice(val id: String, val name: String, val rssi: Int?)

class MainActivity : ComponentActivity() {
    private val bleViewModel: BleViewModel by viewModels {
        BleViewModel.factory(application)
    }
    private val voiceTesterViewModel: VoiceTesterViewModel by viewModels {
        VoiceTesterViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GraspEaseApp(
                bleViewModel = bleViewModel,
                voiceTesterViewModel = voiceTesterViewModel,
            )
        }
    }
}

class BleViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = BleController(application.applicationContext)

    val scanResults: StateFlow<List<BleDevice>> = controller.scanResults
    val isScanning: StateFlow<Boolean> = controller.isScanning
    val isConnected: StateFlow<Boolean> = controller.isConnected
    val connectionStatus: StateFlow<String> = controller.connectionStatus

    fun startScan() = controller.startScan()
    fun stopScan() = controller.stopScan()

    fun connectTo(device: BleDevice) {
        viewModelScope.launch { controller.connectTo(device) }
    }

    fun sendCommand(cmd: Int) {
        viewModelScope.launch { controller.sendCommand(cmd) }
    }

    fun ensureBluetoothOn(): Boolean = controller.isBluetoothReady()

    fun missingBlePermissions(context: Context): List<String> = controller.missingBlePermissions(context)
    fun missingAudioPermission(context: Context): List<String> = controller.missingAudioPermission(context)

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BleViewModel(app) as T
        }
    }
}

class BleController(private val context: Context) {
    private val bluetoothManager: BluetoothManager? = context.getSystemService()
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    private val _isScanning = MutableStateFlow(false)
    private val _isConnected = MutableStateFlow(false)
    private val _connectionStatus = MutableStateFlow("Disconnected")

    val scanResults: StateFlow<List<BleDevice>> = _scanResults
    val isScanning: StateFlow<Boolean> = _isScanning
    val isConnected: StateFlow<Boolean> = _isConnected
    val connectionStatus: StateFlow<String> = _connectionStatus

    fun isBluetoothReady(): Boolean = adapter?.isEnabled == true

    fun missingBlePermissions(context: Context): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        }
        return permissions.distinct().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun missingAudioPermission(context: Context): List<String> =
        listOf(Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun startScan() {
        if (_isScanning.value) return
        val adapter = adapter ?: run {
            _connectionStatus.value = "Bluetooth adapter unavailable"
            return
        }
        if (!adapter.isEnabled) {
            _connectionStatus.value = "Enable Bluetooth to scan"
            return
        }

        _scanResults.value = emptyList()
        _isScanning.value = true
        _connectionStatus.value = "Scanning for orthosis..."

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(emptyList<ScanFilter>(), settings, scanCallback)
        } catch (_: SecurityException) {
            _isScanning.value = false
            _connectionStatus.value = "Missing Bluetooth permissions"
        }

        scope.launch {
            delay(12000)
            stopScan()
        }
    }

    fun stopScan() {
        if (_isScanning.value) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
            }
        }
        _isScanning.value = false
    }

    @Suppress("MissingPermission")
    suspend fun connectTo(device: BleDevice) {
        stopScan()
        val adapter = adapter ?: return
        try {
            gatt?.close()
            _connectionStatus.value = "Connecting to ${device.displayName()}..."
            _isConnected.value = false
            val bt: BluetoothDevice = adapter.getRemoteDevice(device.id)
            gatt = bt.connectGatt(context, false, gattCallback)
        } catch (_: SecurityException) {
            _connectionStatus.value = "Bluetooth permission denied"
        } catch (ex: IllegalArgumentException) {
            _connectionStatus.value = "Invalid device: ${ex.message}"
        }
    }

    @Suppress("MissingPermission")
    suspend fun sendCommand(cmd: Int) {
        val characteristic = txCharacteristic ?: return
        val currentGatt = gatt ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeCharacteristic(
                    characteristic,
                    byteArrayOf(cmd.toByte()),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
            } else {
                characteristic.value = byteArrayOf(cmd.toByte())
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                currentGatt.writeCharacteristic(characteristic)
            }
            _connectionStatus.value = "Sent command 0x${cmd.toString(16)}"
        } catch (_: SecurityException) {
            _connectionStatus.value = "Bluetooth permission denied"
        }
    }

    fun close() {
        stopScan()
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val name = (device.name ?: "").lowercase()
            val matches = name.contains("dextra") || name.contains("orthosis")
            if (!matches) return
            val bleDevice = BleDevice(
                id = device.address,
                name = device.name ?: "Unnamed device",
                rssi = result.rssi,
            )
            val current = _scanResults.value
            if (current.any { it.id == bleDevice.id }) return
            _scanResults.value = current + bleDevice
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            _connectionStatus.value = "Scan failed (code $errorCode)"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _isConnected.value = false
                _connectionStatus.value = "Connection failed ($status)"
                gatt.close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionStatus.value = "Discovering services..."
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _isConnected.value = false
                    txCharacteristic = null
                    _connectionStatus.value = "Disconnected"
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionStatus.value = "Service discovery failed ($status)"
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(TX_CHAR_UUID)
            if (service == null || characteristic == null) {
                _connectionStatus.value = "Orthosis characteristic not found"
                gatt.disconnect()
                return
            }
            txCharacteristic = characteristic
            _isConnected.value = true
            _connectionStatus.value = "Connected to ${gatt.device.name ?: gatt.device.address}"
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionStatus.value = "Command failed (code $status)"
            }
        }
    }
}

fun BleDevice.displayName(): String = if (name.isBlank()) "Unnamed device" else name

private enum class AppScreen { Home, Control, Exercise }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GraspEaseApp(
    bleViewModel: BleViewModel,
    voiceTesterViewModel: VoiceTesterViewModel,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(AppScreen.Home) }
    var showDevices by remember { mutableStateOf(false) }

    val scanResults by bleViewModel.scanResults.collectAsState()
    val isScanning by bleViewModel.isScanning.collectAsState()
    val isConnected by bleViewModel.isConnected.collectAsState()
    val connectionStatus by bleViewModel.connectionStatus.collectAsState()
    val voiceTesterState by voiceTesterViewModel.state.collectAsState()
    var pendingVoiceTest by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val allGranted = grantResults.values.all { it }
        if (allGranted) {
            bleViewModel.startScan()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    "Bluetooth, Nearby Devices, and Location permissions are required to scan.",
                )
            }
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (bleViewModel.ensureBluetoothOn()) {
            bleViewModel.startScan()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Bluetooth remains disabled.")
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingVoiceTest) {
            if (granted) {
                voiceTesterViewModel.runVoiceTest()
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        "Microphone permission is required to run the voice control tester.",
                    )
                }
            }
            pendingVoiceTest = false
        }
    }

    val requestScan = {
        val missing = bleViewModel.missingBlePermissions(context)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else if (!bleViewModel.ensureBluetoothOn()) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            bleViewModel.startScan()
        }
    }

    val startVoiceTester = {
        val missingAudio = bleViewModel.missingAudioPermission(context)
        if (missingAudio.isNotEmpty()) {
            pendingVoiceTest = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            voiceTesterViewModel.runVoiceTest()
        }
    }

    val stopVoiceTester = {
        voiceTesterViewModel.cancelVoiceTest()
        pendingVoiceTest = false
    }

    LaunchedEffect(Unit) {
        val missingAudio = bleViewModel.missingAudioPermission(context)
        if (missingAudio.isNotEmpty()) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val colors = darkColorScheme(
        primary = Color(0xFF1E88E5),
        secondary = Color(0xFFFF9800),
        background = Color(0xFF0B1A2A),
        surface = Color(0xFF162B45),
        onPrimary = Color.White,
        onSecondary = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White,
    )

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("GraspEase (A Sentinel Aura Product)") },
                    actions = {
                        ConnectionIcon(
                            isConnected = isConnected,
                            onClick = { showDevices = true },
                        )
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0B1A2A), Color(0xFF102943)),
                        ),
                    )
                    .padding(innerPadding),
            ) {
                when (screen) {
                    AppScreen.Home -> HomeScreen(
                        isConnected = isConnected,
                        connectionStatus = connectionStatus,
                        onOpenControl = { screen = AppScreen.Control },
                        onOpenExercise = { screen = AppScreen.Exercise },
                        voiceTesterState = voiceTesterState,
                        onVoiceTest = startVoiceTester,
                        onStopVoiceTest = stopVoiceTester,
                    )

                    AppScreen.Control -> ControlScreen(
                        onBack = { screen = AppScreen.Home },
                        onCommand = bleViewModel::sendCommand,
                        isConnected = isConnected,
                    )

                    AppScreen.Exercise -> ExerciseScreen(
                        onBack = { screen = AppScreen.Home },
                        onCommand = bleViewModel::sendCommand,
                        isConnected = isConnected,
                    )
                }

                if (showDevices) {
                    DeviceDialog(
                        isScanning = isScanning,
                        devices = scanResults,
                        status = connectionStatus,
                        onScan = requestScan,
                        onDismiss = { showDevices = false },
                        onConnect = { device ->
                            bleViewModel.connectTo(device)
                            showDevices = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionIcon(isConnected: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.TopEnd) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                contentDescription = "Bluetooth",
                tint = if (isConnected) Color(0xFF1E88E5) else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!isConnected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color.Red, shape = CircleShape)
                    .align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
fun HomeScreen(
    isConnected: Boolean,
    connectionStatus: String,
    onOpenControl: () -> Unit,
    onOpenExercise: () -> Unit,
    voiceTesterState: VoiceTesterState,
    onVoiceTest: () -> Unit,
    onStopVoiceTest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (isConnected) "Connected" else "Tap Bluetooth to connect",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = connectionStatus,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }

        ModeButton(
            label = "CONTROL MODE",
            icon = Icons.Default.SportsEsports,
            enabled = isConnected,
            accent = Color(0xFF1E88E5),
            onClick = onOpenControl,
        )

        ModeButton(
            label = "EXERCISE MODE",
            icon = Icons.Default.MedicalServices,
            enabled = isConnected,
            accent = Color(0xFFFF9800),
            onClick = onOpenExercise,
        )

        VoiceTesterCard(
            state = voiceTesterState,
            onStart = onVoiceTest,
            onStop = onStopVoiceTest,
        )
    }
}

@Composable
fun ModeButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val color = if (enabled) accent else Color.Gray
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun VoiceTesterCard(
    state: VoiceTesterState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val accent = Color(0xFF26C6DA)
    val canStart = state.modelReady && !state.isRecording && !state.isTranscribing
    val canStop = state.isRecording || state.isTranscribing
    val subtitle = when {
        state.error != null -> state.error
        state.isRecording || state.isTranscribing -> "Streaming live... speak now"
        !state.modelReady -> state.status
        else -> state.status
    }
    val transcriptionPreview = if (state.transcription.isNotBlank()) {
        state.transcription.trim()
    } else if (state.isRecording || state.isTranscribing) {
        "Listening... transcript will appear here."
    } else {
        "Tap Start to stream live transcription."
    }
    val latencyText = state.latencyMs?.let { "Latency (first partial): ${it} ms" } ?: "Latency: --"
    val detailLines = if (state.details.isEmpty()) listOf("Events will appear here.") else state.details

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 170.dp)
            .alpha(if (state.modelReady) 1f else 0.6f),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "VOICE CONTROL TESTER",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Icon(
                    imageVector = if (state.isRecording) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = "Voice tester",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            Surface(
                color = Color.Black.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Transcript",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = transcriptionPreview,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Diagnostics",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = latencyText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    detailLines.forEach { line ->
                        Text(
                            text = "• $line",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Button(
                onClick = { if (canStop) onStop() else onStart() },
                enabled = canStart || canStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = accent,
                    disabledContainerColor = Color.LightGray.copy(alpha = 0.4f),
                    disabledContentColor = Color.DarkGray,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (canStop) "Stop" else "Start", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ControlScreen(
    onBack: () -> Unit,
    onCommand: (Int) -> Unit,
    isConnected: Boolean,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val voiceController = remember { CloudSttController() }
    var listeningStatus by remember { mutableStateOf("Cloud STT idle") }
    var listeningError by remember { mutableStateOf<String?>(null) }
    val sttConfig = remember {
        CloudSttConfig(
            apiKey = null,
            accessToken = BuildConfig.CLOUD_STT_BEARER.takeIf { it.isNotBlank() },
            tokenEndpoint = BuildConfig.CLOUD_STT_TOKEN_ENDPOINT.takeIf { it.isNotBlank() },
            projectId = "fabled-opus-483704-t6",
            location = "asia-southeast1",
            recognizerId = "default",
            languageCode = "en-US", // en-IN not available for latest_long/asia-southeast1; using en-US
        )
    }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            if (sttConfig.accessToken.isNullOrBlank() && sttConfig.tokenEndpoint.isNullOrBlank()) {
                listeningStatus = "Missing bearer token. Set CLOUD_STT_BEARER or CLOUD_STT_TOKEN_ENDPOINT."
                listeningError = "Missing token"
                return@LaunchedEffect
            }
            voiceController.start(
                scope = coroutineScope,
                config = sttConfig,
                onCommand = onCommand,
                onStatus = { status ->
                    listeningStatus = status
                    listeningError = null
                },
                onError = { listeningError = it },
            )
        } else {
            listeningStatus = "Cloud STT idle"
            listeningError = null
            voiceController.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose { voiceController.stop() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            CommandTile(label = "GRASP", color = Color(0xFF2E7D32)) { onCommand(0x01) }
            CommandTile(label = "HOLD", color = Color(0xFF1565C0)) { onCommand(0x02) }
            CommandTile(label = "RELEASE", color = Color(0xFFD32F2F)) { onCommand(0x03) }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                text = listeningError ?: listeningStatus,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        FloatingActionButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Icon(Icons.Default.Close, contentDescription = "Back")
        }
    }
}

@Composable
fun ExerciseScreen(
    onBack: () -> Unit,
    onCommand: (Int) -> Unit,
    isConnected: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            CommandTile(label = "START REHAB", color = Color(0xFFFF9800)) { onCommand(CMD_START_REHAB) }
            CommandTile(label = "STOP", color = Color(0xFFD32F2F)) { onCommand(0x03) }
        }

        FloatingActionButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Icon(Icons.Default.Close, contentDescription = "Back")
        }
    }
}

@Composable
fun CommandTile(label: String, color: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun DeviceDialog(
    isScanning: Boolean,
    devices: List<BleDevice>,
    status: String,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
    onConnect: (BleDevice) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onScan) {
                Text(if (isScanning) "Scanning..." else "Scan Again")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Bluetooth Connection") },
        text = {
            Column {
                Text(text = status, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.height(8.dp))
                if (isScanning) {
                    Text("Searching for nearby devices...")
                }
                if (devices.isEmpty()) {
                    Text("No orthosis devices found yet.")
                } else {
                    devices.forEachIndexed { index, device ->
                        DeviceRow(device = device, onClick = { onConnect(device) })
                        if (index != devices.lastIndex) {
                            Divider(modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun DeviceRow(device: BleDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = device.displayName(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = device.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            text = device.rssi?.let { "$it dBm" } ?: "--",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun VoiceControlButton(
    modifier: Modifier = Modifier,
    isEnabled: Boolean,
    onCommand: (Int) -> Unit,
) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Voice control idle") }
    val coroutineScope = rememberCoroutineScope()
    val updatedOnCommand by rememberUpdatedState(onCommand)

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.stopListening()
            speechRecognizer.destroy()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startListening(
                context = context,
                recognizer = speechRecognizer,
                onStatus = { status = it },
                onListeningChanged = { isListening = it },
                onCommand = { cmd -> coroutineScope.launch { updatedOnCommand(cmd) } },
            )
        } else {
            status = "Microphone permission denied"
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusChip(text = status)
        Spacer(modifier = Modifier.height(8.dp))
        FloatingActionButton(
            onClick = {
                if (!isEnabled) {
                    status = "Connect to the orthosis first"
                    return@FloatingActionButton
                }
                val missing = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
                if (missing) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    startListening(
                        context = context,
                        recognizer = speechRecognizer,
                        onStatus = { status = it },
                        onListeningChanged = { isListening = it },
                        onCommand = { cmd -> coroutineScope.launch { updatedOnCommand(cmd) } },
                    )
                }
            },
            containerColor = if (isListening) Color.Red else Color(0xFF1E88E5),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Voice",
                    tint = Color.White,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isListening) "Stop" else "Voice",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
fun StatusChip(text: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun startListening(
    context: Context,
    recognizer: SpeechRecognizer,
    onStatus: (String) -> Unit,
    onListeningChanged: (Boolean) -> Unit,
    onCommand: (Int) -> Unit,
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onStatus("Voice recognition not available on this device.")
        return
    }

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    val handler = Handler(Looper.getMainLooper())
    val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onStatus("Listening... say grasp, hold, or release")
            onListeningChanged(true)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            onListeningChanged(false)
        }

        override fun onError(error: Int) {
            onStatus("Voice error: ${describeError(error)}")
            onListeningChanged(false)
        }

        override fun onResults(results: Bundle?) {
            onListeningChanged(false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.lowercase()
                ?.trim()
            if (text.isNullOrBlank()) {
                onStatus("No speech detected. Try again.")
                return
            }
            val command = parseVoiceCommand(text)
            if (command == null) {
                onStatus("Command not recognized: \"$text\"")
            } else {
                onStatus("Sending ${voiceLabel(command)}...")
                handler.post { onCommand(command) }
                onStatus("${voiceLabel(command)} sent.")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    recognizer.setRecognitionListener(listener)
    recognizer.startListening(intent)
}

private fun parseVoiceCommand(text: String): Int? = when {
    text.contains("grasp") -> 0x01
    text.contains("hold") -> 0x02
    text.contains("release") || text.contains("open") -> 0x03
    else -> null
}

private fun voiceLabel(cmd: Int): String = when (cmd) {
    0x01 -> "GRASP"
    0x02 -> "HOLD"
    0x03 -> "RELEASE"
    CMD_START_REHAB -> "EXERCISE"
    else -> "CMD $cmd"
}

private fun describeError(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "Audio recording issue"
    SpeechRecognizer.ERROR_CLIENT -> "Client error"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
    SpeechRecognizer.ERROR_NETWORK -> "Network error"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
    SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
    SpeechRecognizer.ERROR_SERVER -> "Server error"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
    else -> "Unknown ($code)"
}
