package com.mp3os.player

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ================================================================ picked apps (stored on the phone)

object AppPrefs {
    private const val FILE = "mp3os"
    private const val KEY = "apps"

    fun load(context: Context): Set<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY, null).orEmpty().toSet()

    fun save(context: Context, packages: Set<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putStringSet(KEY, packages).apply()
    }
}

// ================================================================ clock in the (hidden) status bar area

@Composable
fun StatusClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val time by produceState("") {
        while (true) {
            val now = System.currentTimeMillis()
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            value = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now))
            delay(60_000L - now % 60_000L + 50L)
        }
    }
    Box(
        modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility),
        contentAlignment = Alignment.Center,
    ) {
        Text(time, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ================================================================ helpers

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Opens the first system screen that exists on this phone. */
private fun openSystem(context: Context, vararg actions: String) {
    for (action in actions) {
        val ok = runCatching {
            val intent = Intent(action)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
        if (ok) return
    }
}

private data class Quick(val wifi: Boolean, val bluetooth: Boolean, val airplane: Boolean, val location: Boolean)

private fun readQuick(context: Context): Quick {
    val wifi = runCatching {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    }.getOrDefault(false)
    val bluetooth = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)
    val airplane = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }.getOrDefault(false)
    val location = runCatching {
        LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
    }.getOrDefault(false)
    return Quick(wifi, bluetooth, airplane, location)
}

private fun readBrightness(context: Context): Float {
    val own = context.findActivity()?.window?.attributes?.screenBrightness ?: -1f
    if (own >= 0f) return own
    return runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f)
}

/** System-wide when the "modify system settings" permission is granted, otherwise only this app's window. */
private fun applyBrightness(context: Context, value: Float) {
    val v = value.coerceIn(0.02f, 1f)
    val window = context.findActivity()?.window
    if (Settings.System.canWrite(context)) {
        runCatching {
            val cr = context.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (v * 255f).roundToInt())
        }
        window?.let {
            val lp = it.attributes
            if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = lp
            }
        }
    } else {
        window?.let {
            val lp = it.attributes
            lp.screenBrightness = v
            it.attributes = lp
        }
    }
}

// ================================================================ Settings tab

@Suppress("DEPRECATION")
@Composable
fun SettingsScreen(
    pickedApps: List<AppEntry>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var volumeDragging by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(readBrightness(context)) }
    var canWrite by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var quick by remember { mutableStateOf(readQuick(context)) }
    var torchOn by remember { mutableStateOf(false) }

    // follows the hardware volume keys and the toggles changed in the system panels
    LaunchedEffect(Unit) {
        while (true) {
            if (!volumeDragging) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            canWrite = Settings.System.canWrite(context)
            quick = readQuick(context)
            delay(700)
        }
    }

    // flashlight (needs no permission)
    val camera = remember { context.getSystemService(CameraManager::class.java) }
    val torchId = remember {
        runCatching {
            camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }
    DisposableEffect(camera) {
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchId) torchOn = enabled
            }
        }
        runCatching { camera.registerTorchCallback(callback, null) }
        onDispose { runCatching { camera.unregisterTorchCallback(callback) } }
    }

    val tiles = buildList {
        add(Tile(Icons.Filled.Wifi, "Wi-Fi", quick.wifi) {
            if (Build.VERSION.SDK_INT >= 29) {
                openSystem(context, Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
            } else {
                runCatching {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wm.isWifiEnabled = !wm.isWifiEnabled
                }.onFailure { openSystem(context, Settings.ACTION_WIFI_SETTINGS) }
            }
        })
        add(Tile(Icons.Filled.Bluetooth, "Bluetooth", quick.bluetooth) {
            openSystem(context, Settings.ACTION_BLUETOOTH_SETTINGS)
        })
        add(Tile(Icons.Filled.SignalCellularAlt, "Mobile data", false) {
            if (Build.VERSION.SDK_INT >= 29) {
                openSystem(context, Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)
            } else {
                openSystem(context, Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
            }
        })
        add(Tile(Icons.Filled.AirplanemodeActive, "Airplane mode", quick.airplane) {
            openSystem(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
        })
        add(Tile(Icons.Filled.LocationOn, "Location", quick.location) {
            openSystem(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        })
        if (torchId != null) {
            add(Tile(Icons.Filled.FlashlightOn, "Flashlight", torchOn) {
                runCatching { camera.setTorchMode(torchId, !torchOn) }
            })
        }
        add(Tile(Icons.Filled.BatterySaver, "Battery saver", false) {
            openSystem(context, Settings.ACTION_BATTERY_SAVER_SETTINGS, Settings.ACTION_SETTINGS)
        })
        add(Tile(Icons.Filled.Brightness6, "Display", false) {
            openSystem(context, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)
        })
        add(Tile(Icons.Filled.SettingsIcon, "All settings", false) {
            openSystem(context, Settings.ACTION_SETTINGS)
        })
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Settings",
            Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SliderCard(
                icon = Icons.Filled.VolumeUp,
                title = "Volume",
                valueText = "${(volume / maxVolume * 100f).roundToInt()}%",
                value = volume,
                range = 0f..maxVolume.toFloat(),
                onChange = {
                    volumeDragging = true
                    volume = it
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.roundToInt(), 0) }
                },
                onFinish = { volumeDragging = false },
            )

            SliderCard(
                icon = Icons.Filled.Brightness6,
                title = "Brightness",
                valueText = "${(brightness * 100f).roundToInt()}%",
                value = brightness,
                range = 0.02f..1f,
                onChange = {
                    brightness = it
                    applyBrightness(context, it)
                },
                onFinish = {},
            ) {
                if (!canWrite) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }) { Text("Only this app is dimmed. Tap to allow system-wide brightness") }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("Quick settings", color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { t -> QuickTile(t.icon, t.label, t.on, Modifier.weight(1f), t.onClick) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("Apps", color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            Surface(
                onClick = onAdd,
                shape = RoundedCornerShape(28.dp),
                color = cs.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, tint = cs.onPrimaryContainer)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Add an app", color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold)
                        Text(
                            "Only apps you pick here show in the Apps tab",
                            color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            pickedApps.forEach { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(app.icon, app.label, Modifier.size(40.dp))
                    Text(
                        app.label,
                        Modifier.weight(1f).padding(horizontal = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurface,
                    )
                    FilledTonalIconButton(onClick = { onRemove(app.packageName) }) {
                        Icon(Icons.Filled.Close, "Remove ${app.label}")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private class Tile(val icon: ImageVector, val label: String, val on: Boolean, val onClick: () -> Unit)

@Composable
private fun QuickTile(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (on) cs.primaryContainer else cs.surfaceContainerHigh,
        springEffects(),
        label = "tileBg",
    )
    val fg = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(if (on) 32.dp else 22.dp),
        color = bg,
        modifier = modifier.height(68.dp),
    ) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = fg)
            Spacer(Modifier.width(12.dp))
            Text(label, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SliderCard(
    icon: ImageVector,
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(32.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = cs.onSurfaceVariant)
                Text(
                    title,
                    Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
                Text(valueText, color = cs.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = value,
                onValueChange = onChange,
                onValueChangeFinished = onFinish,
                valueRange = range,
            )
            footer()
        }
    }
}

// ================================================================ "Add an app" picker (full-screen overlay, no dialog window)

@Composable
fun AppPicker(
    all: List<AppEntry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Add an app",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
                Button(onClick = onClose) { Text("Done") }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(all, key = { it.packageName }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onToggle(app.packageName) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(app.icon, app.label, Modifier.size(44.dp))
                        Text(
                            app.label,
                            Modifier.weight(1f).padding(horizontal = 14.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = cs.onSurface,
                        )
                        Checkbox(checked = app.packageName in selected, onCheckedChange = null)
                    }
                }
            }
        }
    }
}
