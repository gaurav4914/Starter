package com.netpulse.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.net.TrafficStats
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val PREFS = "netpulse_prefs"
private const val KEY_MANUAL_RESET = "manual_reset_at"
private const val KEY_RUNNING = "monitor_running"
private const val KEY_AUTO_START = "auto_start_boot"
private const val KEY_BUDGET_MB = "budget_mb"
private const val KEY_ALERTED_CYCLE = "alerted_cycle"

data class UsageSnapshot(
    val mobileRx: Long = 0L, val mobileTx: Long = 0L,
    val wifiRx: Long = 0L, val wifiTx: Long = 0L,
    val cycleStart: Long = 0L, val permissionRequired: Boolean = false,
    val error: String? = null
) {
    val mobile: Long get() = mobileRx + mobileTx
    val wifi: Long get() = wifiRx + wifiTx
    val total: Long get() = mobile + wifi
}
data class HistoryItem(val label: String, val bytes: Long)

object UsageRepository {
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun cycleStart(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (now < c.timeInMillis) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.timeInMillis
    }

    fun activeStart(context: Context, now: Long = System.currentTimeMillis()): Long {
        val natural = cycleStart(now)
        val manual = prefs(context).getLong(KEY_MANUAL_RESET, 0L)
        return if (manual > natural && manual <= now) manual else natural
    }

    fun resetNow(context: Context) {
        prefs(context).edit().putLong(KEY_MANUAL_RESET, System.currentTimeMillis()).apply()
    }

    fun load(context: Context, now: Long = System.currentTimeMillis()): UsageSnapshot {
        val start = activeStart(context, now)
        if (!hasUsageAccess(context)) return UsageSnapshot(cycleStart = start, permissionRequired = true)
        return try {
            val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val m = read(manager, ConnectivityManager.TYPE_MOBILE, start, now)
            val w = read(manager, ConnectivityManager.TYPE_WIFI, start, now)
            UsageSnapshot(m.first, m.second, w.first, w.second, start)
        } catch (e: SecurityException) {
            UsageSnapshot(cycleStart = start, permissionRequired = true, error = "Usage Access अनुमति दोबारा चालू करें।")
        } catch (e: Exception) {
            UsageSnapshot(cycleStart = start, error = e.message ?: "Android से डेटा नहीं पढ़ा जा सका।")
        }
    }

    fun history(context: Context, now: Long): List<HistoryItem> {
        if (!hasUsageAccess(context)) return emptyList()
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val today = cycleStart(now)
        val fmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        return (6 downTo 1).mapNotNull { offset ->
            val c = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -offset) }
            val start = c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
            val end = c.timeInMillis
            try {
                val m = read(manager, ConnectivityManager.TYPE_MOBILE, start, end)
                val w = read(manager, ConnectivityManager.TYPE_WIFI, start, end)
                HistoryItem(fmt.format(Date(start)), m.first + m.second + w.first + w.second)
            } catch (_: Exception) { null }
        }
    }

    private fun read(manager: NetworkStatsManager, type: Int, start: Long, end: Long): Pair<Long, Long> {
        val bucket = manager.querySummaryForDevice(type, null, start, end)
        return bucket.rxBytes to bucket.txBytes
    }

    fun bytes(value: Long): String {
        if (value < 1024L) return value.toString() + " B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble() / 1024.0
        var index = 0
        while (amount >= 1024.0 && index < units.lastIndex) { amount /= 1024.0; index++ }
        return String.format(Locale.getDefault(), "%.2f %s", amount, units[index])
    }

    fun speed(value: Long): String {
        val bits = value.coerceAtLeast(0L) * 8.0
        return when {
            bits >= 1_000_000.0 -> String.format(Locale.getDefault(), "%.2f Mbps", bits / 1_000_000.0)
            bits >= 1_000.0 -> String.format(Locale.getDefault(), "%.0f Kbps", bits / 1_000.0)
            else -> String.format(Locale.getDefault(), "%.0f bps", bits)
        }
    }

    fun cycleLabel(start: Long): String =
        SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(start))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 enforces edge-to-edge for targetSdk 35. Keep the app content
        // inside system bars using Compose insets, while matching the dark header.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.rgb(8, 14, 28)
        window.navigationBarColor = android.graphics.Color.rgb(8, 14, 28)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF5EEAD4), secondary = Color(0xFF93C5FD),
                background = Color(0xFF080E1C), surface = Color(0xFF111B2E)
            )) { Surface(color = Color(0xFF080E1C)) { NetPulseScreen() } }
        }
    }
}

@Composable
private fun NetPulseScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val prefs = remember { UsageRepository.prefs(context) }
    var usage by remember { mutableStateOf(UsageSnapshot()) }
    var history by remember { mutableStateOf(emptyList<HistoryItem>()) }
    var monitoring by remember { mutableStateOf(prefs.getBoolean(KEY_RUNNING, false)) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_START, true)) }
    var budget by remember { mutableStateOf(prefs.getLong(KEY_BUDGET_MB, 1024L).toString()) }
    var down by remember { mutableStateOf(0L) }
    var up by remember { mutableStateOf(0L) }
    var oldRx by remember { mutableStateOf(TrafficStats.getTotalRxBytes()) }
    var oldTx by remember { mutableStateOf(TrafficStats.getTotalTxBytes()) }
    var oldAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var confirmReset by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf(0) }
    var peakDown by remember { mutableStateOf(0L) }
    var peakUp by remember { mutableStateOf(0L) }
    var speedSamples by remember { mutableStateOf(List(24) { 0L }) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && UsageRepository.hasUsageAccess(context)) MonitorService.start(context)
    }

    fun startMonitoring() {
        if (!UsageRepository.hasUsageAccess(context)) {
            activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else MonitorService.start(context)
    }

    LaunchedEffect(Unit) {
        var lastHistory = 0L
        while (true) {
            val now = System.currentTimeMillis()
            val elapsed = (now - oldAt).coerceAtLeast(1L)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            down = if (rx >= oldRx) (rx - oldRx) * 1000L / elapsed else 0L
            up = if (tx >= oldTx) (tx - oldTx) * 1000L / elapsed else 0L
            oldRx = rx; oldTx = tx; oldAt = now
            peakDown = maxOf(peakDown, down)
            peakUp = maxOf(peakUp, up)
            speedSamples = speedSamples.drop(1) + maxOf(down, up)
            usage = UsageRepository.load(context, now)
            monitoring = prefs.getBoolean(KEY_RUNNING, false)
            if (now - lastHistory > 60_000L) {
                history = UsageRepository.history(context, now)
                lastHistory = now
            }
            delay(1000L)
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF080E1C))
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF164E63))), RoundedCornerShape(16.dp)).padding(12.dp)) {
                Icon(Icons.Filled.Bolt, null, tint = Color(0xFF99F6E4))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("NETPULSE", color = Color(0xFF99F6E4), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Internet speed & data monitor", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Live network insights", color = Color(0xFF9FB3C8), fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (monitoring) "● LIVE" else "READY", color = if (monitoring) Color(0xFF5EEAD4) else Color(0xFFFBBF24), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("v1.1", color = Color(0xFF94A3B8), fontSize = 10.sp)
            }
        }

        ModeSelector(selectedMode = selectedMode, onSelected = { selectedMode = it })

        if (selectedMode == 1) {
            LiveSpeedMode(
                down = down,
                up = up,
                peakDown = peakDown,
                peakUp = peakUp,
                samples = speedSamples,
                monitoring = monitoring,
                onStart = { startMonitoring() },
                onStop = {
                    activity.startService(Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
                }
            )
        } else {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF10283A))) {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF103B48), Color(0xFF142440), Color(0xFF17213B)))).padding(19.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Speed, null, tint = Color(0xFF5EEAD4))
                    Text(" LIVE NETWORK SPEED", Modifier.padding(start = 8.dp), color = Color(0xFFB8C9DB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(if (monitoring) "MONITORING" else "PAUSED", color = if (monitoring) Color(0xFF5EEAD4) else Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeedTile("DOWNLOAD", "↓ " + UsageRepository.speed(down), Modifier.weight(1f))
                    SpeedTile("UPLOAD", "↑ " + UsageRepository.speed(up), Modifier.weight(1f))
                }
                Text("Live device-wide speed • Wi-Fi + mobile", color = Color(0xFF9FB3C8), fontSize = 11.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UsageTile("TOTAL USED", UsageRepository.bytes(usage.total), "Mobile + Wi-Fi", Modifier.weight(1f))
            UsageTile("MOBILE DATA", UsageRepository.bytes(usage.mobile), "SIM / cellular", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UsageTile("WI-FI DATA", UsageRepository.bytes(usage.wifi), "Wireless network", Modifier.weight(1f))
            UsageTile("CYCLE START", UsageRepository.cycleLabel(usage.cycleStart), "Daily at 1:00 AM", Modifier.weight(1f))
        }

        if (usage.permissionRequired) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF422B19)), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("एक जरूरी अनुमति दें", color = Color(0xFFFDE68A), fontWeight = FontWeight.Bold)
                    Text("Daily usage पढ़ने के लिए Android Settings में NetPulse को Usage Access देना होगा।", color = Color(0xFFFDE68A), fontSize = 12.sp)
                    Button(onClick = { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Usage Access खोलें") }
                }
            }
        } else if (usage.error != null) {
            Text(usage.error ?: "", color = Color(0xFFFCA5A5), fontSize = 12.sp)
        }

        SectionCard("DAILY DATA CYCLE") {
            Text("हर दिन रात 1:00 AM पर नया दिन शुरू होगा।", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("आज का काउंटर 1 बजे से अभी तक का मोबाइल + Wi-Fi डेटा दिखाता है। पुराने दिन history में रहेंगे।", color = Color(0xFF9FB3C8), fontSize = 12.sp)
            Button(onClick = { confirmReset = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24435A))) {
                Icon(Icons.Filled.Refresh, null); Text("  अभी काउंटर रीसेट करें")
            }
        }

        SectionCard("SMART DATA ALERT") {
            Text("Daily data budget (MB)", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = budget, onValueChange = { budget = it.filter(Char::isDigit).take(7) },
                    modifier = Modifier.weight(1f), label = { Text("Limit in MB") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Button(onClick = {
                    val value = budget.toLongOrNull()?.coerceIn(0L, 1_000_000L) ?: 1024L
                    prefs.edit().putLong(KEY_BUDGET_MB, value).apply()
                    budget = value.toString()
                }) { Text("Save") }
            }
            Text("सीमा पूरी होने पर एक notification आएगा। 0 रखने पर alert बंद रहेगा।", color = Color(0xFF9FB3C8), fontSize = 11.sp)
        }

        SectionCard("MONITOR CONTROLS") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Boot पर auto-start", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("फोन restart पर monitoring चालू करें", color = Color(0xFF9FB3C8), fontSize = 11.sp)
                }
                Switch(checked = autoStart, onCheckedChange = {
                    autoStart = it; prefs.edit().putBoolean(KEY_AUTO_START, it).apply()
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { startMonitoring() }, enabled = !monitoring, modifier = Modifier.weight(1f)) { Text("Start monitor") }
                Button(
                    onClick = { activity.startService(Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)) },
                    enabled = monitoring, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D))
                ) { Text("Stop monitor") }
            }
            Text("Ongoing notification में live speed और आज का usage दिखेगा।", color = Color(0xFF9FB3C8), fontSize = 11.sp)
        }

        SectionCard("LAST 6 COMPLETED CYCLES") {
            if (history.isEmpty()) Text("History के लिए Usage Access चालू करें।", color = Color(0xFF9FB3C8), fontSize = 12.sp)
            history.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, color = Color(0xFFCBD5E1), modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Text(UsageRepository.bytes(item.bytes), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                val maximum = history.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF26364D), RoundedCornerShape(10.dp))) {
                    Box(Modifier.fillMaxWidth((item.bytes.toFloat() / maximum).coerceIn(0f, 1f)).height(4.dp).background(Color(0xFF2DD4BF), RoundedCornerShape(10.dp)))
                }
            }
        }

        }
        Text(
            "डेटा Android के Network Usage records से आता है; carrier billing से अंतर हो सकता है। Live speed पूरे डिवाइस के traffic का अनुमान है।",
            color = Color(0xFF64748B), fontSize = 10.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("आज का काउंटर रीसेट करें?") },
            text = { Text("अभी से नया counter शुरू होगा। अगला automatic cycle रात 1:00 AM पर होगा।") },
            confirmButton = {
                TextButton(onClick = {
                    UsageRepository.resetNow(context)
                    usage = UsageRepository.load(context)
                    confirmReset = false
                }) { Text("रीसेट करें") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("रद्द करें") } }
        )
    }
}

@Composable
private fun ModeSelector(selectedMode: Int, onSelected: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0xFF111B2E), RoundedCornerShape(18.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(
            onClick = { onSelected(0) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedMode == 0) Color(0xFF0F766E) else Color.Transparent,
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Filled.DataUsage, contentDescription = null)
            Text("  Overview")
        }
        Button(
            onClick = { onSelected(1) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedMode == 1) Color(0xFF0F766E) else Color.Transparent,
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Filled.Speed, contentDescription = null)
            Text("  Live speed")
        }
    }
}

@Composable
private fun LiveSpeedMode(
    down: Long,
    up: Long,
    peakDown: Long,
    peakUp: Long,
    samples: List<Long>,
    monitoring: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10283A))
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(Color(0xFF103B48), Color(0xFF142440), Color(0xFF17213B))))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Speed, contentDescription = null, tint = Color(0xFF5EEAD4))
                    Text("  REAL-TIME INTERNET SPEED", color = Color(0xFFB8C9DB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (monitoring) "● LIVE" else "● LOCAL",
                        color = if (monitoring) Color(0xFF5EEAD4) else Color(0xFFFBBF24),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text("Your connection, right now", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeedBigTile(
                        title = "DOWNLOAD",
                        value = UsageRepository.speed(down),
                        direction = "↓ Receiving",
                        modifier = Modifier.weight(1f),
                        accent = Color(0xFF5EEAD4)
                    )
                    SpeedBigTile(
                        title = "UPLOAD",
                        value = UsageRepository.speed(up),
                        direction = "↑ Sending",
                        modifier = Modifier.weight(1f),
                        accent = Color(0xFF93C5FD)
                    )
                }
                Text(
                    "Live values refresh every second. Includes device-wide Wi-Fi and mobile traffic.",
                    color = Color(0xFF9FB3C8),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111B2E))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SESSION PEAK SPEED", color = Color(0xFF99F6E4), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Peak download", color = Color(0xFF9FB3C8), fontSize = 11.sp)
                        Text(UsageRepository.speed(peakDown), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Peak upload", color = Color(0xFF9FB3C8), fontSize = 11.sp)
                        Text(UsageRepository.speed(peakUp), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Peaks are measured since this screen was opened.", color = Color(0xFF64748B), fontSize = 10.sp)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111B2E))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LIVE TRAFFIC ACTIVITY", color = Color(0xFF99F6E4), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().height(74.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    val maximum = (samples.maxOrNull() ?: 1L).coerceAtLeast(1L)
                    samples.forEach { sample ->
                        val fraction = (sample.toFloat() / maximum).coerceIn(0f, 1f)
                        Box(
                            Modifier.weight(1f)
                                .height((6f + 60f * fraction).dp)
                                .background(Color(0xFF2DD4BF), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("24 seconds", color = Color(0xFF64748B), fontSize = 10.sp)
                    Text("Most recent", color = Color(0xFF64748B), fontSize = 10.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = !monitoring,
                modifier = Modifier.weight(1f)
            ) { Text("Start notification") }
            OutlinedButton(
                onClick = onStop,
                enabled = monitoring,
                modifier = Modifier.weight(1f)
            ) { Text("Stop monitor") }
        }
        Text(
            "यह स्पीड फोन के कुल network traffic का अनुमान है। Background apps और VPN traffic भी इसमें शामिल हो सकते हैं; यह carrier-certified speed test नहीं है।",
            color = Color(0xFF64748B),
            fontSize = 10.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun SpeedBigTile(
    title: String,
    value: String,
    direction: String,
    modifier: Modifier,
    accent: Color
) {
    Column(
        modifier.background(Color(0x332D6674), RoundedCornerShape(18.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Color(0xFF9FB3C8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(direction, color = Color(0xFFB8C9DB), fontSize = 10.sp)
    }
}

@Composable
private fun SpeedTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Color(0x332D6674), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(label, color = Color(0xFF9FB3C8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UsageTile(title: String, value: String, caption: String, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF111B2E)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.DataUsage, null, tint = Color(0xFF5EEAD4))
            Text(title, color = Color(0xFF9FB3C8), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(caption, color = Color(0xFF64748B), fontSize = 10.sp)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111B2E)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color(0xFF99F6E4), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
            content()
        }
    }
}

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previousRx = TrafficStats.getTotalRxBytes()
    private var previousTx = TrafficStats.getTotalTxBytes()
    private var previousTime = System.currentTimeMillis()
    private var lastUsageTime = 0L
    private var down = 0L
    private var up = 0L
    private var usage = UsageSnapshot()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(4101, notification())
        UsageRepository.prefs(this).edit().putBoolean(KEY_RUNNING, true).apply()
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsed = (now - previousTime).coerceAtLeast(1L)
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                down = if (rx >= previousRx) (rx - previousRx) * 1000L / elapsed else 0L
                up = if (tx >= previousTx) (tx - previousTx) * 1000L / elapsed else 0L
                previousRx = rx; previousTx = tx; previousTime = now

                if (now - lastUsageTime >= 30_000L || usage.cycleStart != UsageRepository.activeStart(this@MonitorService, now)) {
                    usage = UsageRepository.load(this@MonitorService, now)
                    lastUsageTime = now
                    checkBudget()
                }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(4101, notification())
                delay(1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            UsageRepository.prefs(this).edit().putBoolean(KEY_RUNNING, false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun checkBudget() {
        if (usage.permissionRequired || usage.error != null) return
        val p = UsageRepository.prefs(this)
        val limit = p.getLong(KEY_BUDGET_MB, 1024L)
        if (limit <= 0L || usage.total < limit * 1024L * 1024L || p.getLong(KEY_ALERTED_CYCLE, 0L) == usage.cycleStart) return
        p.edit().putLong(KEY_ALERTED_CYCLE, usage.cycleStart).apply()
        val alert = Notification.Builder(this, "netpulse_alerts")
            .setSmallIcon(R.drawable.ic_stat_netpulse)
            .setContentTitle("NetPulse daily data alert")
            .setContentText("आपका daily budget " + UsageRepository.bytes(limit * 1024L * 1024L) + " पूरा हो गया है।")
            .setAutoCancel(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(4102, alert)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val daily = if (usage.permissionRequired) "Usage Access दें" else UsageRepository.bytes(usage.total)
        return Notification.Builder(this, "netpulse_status")
            .setSmallIcon(R.drawable.ic_stat_netpulse)
            .setContentTitle("↓ " + UsageRepository.speed(down) + "   ↑ " + UsageRepository.speed(up))
            .setContentText("आज: " + daily + "  •  Reset 1:00 AM")
            .setStyle(Notification.BigTextStyle().bigText("आज: " + daily + "\nMobile " + UsageRepository.bytes(usage.mobile) + "  •  Wi-Fi " + UsageRepository.bytes(usage.wifi)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(android.R.drawable.ic_media_pause, "बंद करें", stop)
            .setCategory(Notification.CATEGORY_SERVICE).build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("netpulse_status", "Live speed monitor", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel("netpulse_alerts", "Daily data alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onDestroy() {
        UsageRepository.prefs(this).edit().putBoolean(KEY_RUNNING, false).apply()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.netpulse.app.STOP_MONITOR"
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = UsageRepository.prefs(context)
        if (prefs.getBoolean(KEY_AUTO_START, true) && UsageRepository.hasUsageAccess(context)) {
            try { MonitorService.start(context) } catch (_: Exception) { prefs.edit().putBoolean(KEY_RUNNING, false).apply() }
        }
    }
}
