package com.yourname.cryptofloater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.pow
import android.net.Uri
import androidx.compose.foundation.BorderStroke

// ==========================================
// 核心数据模型与本地存储逻辑
// ==========================================
data class CoinData(val name: String, val odds: Map<Int, Double>)

fun loadAllCoins(prefs: SharedPreferences, gson: Gson): List<CoinData> {
    val json = prefs.getString("all_coins_v2", null)
    if (json != null) {
        try {
            val type = object : TypeToken<List<CoinData>>() {}.type
            val list: List<CoinData> = gson.fromJson(json, type)
            if (list.isNotEmpty()) return list
        } catch (e: Exception) {}
    }
    return listOf(
        CoinData("BTC", mapOf(5 to 0.838, 10 to 0.85, 15 to 0.85, 30 to 0.88, 60 to 0.88)),
        CoinData("ETH", mapOf(5 to 0.828, 10 to 0.838, 15 to 0.838, 30 to 0.868, 60 to 0.868))
    )
}

fun saveAllCoins(prefs: SharedPreferences, gson: Gson, coins: List<CoinData>) {
    prefs.edit().putString("all_coins_v2", gson.toJson(coins)).apply()
}

fun calculateProfit(principal: Double, rate: Double, times: Int): Double = principal * (1.0 + rate).pow(times.toDouble())

fun sendUpdateBroadcast(context: Context, alpha: Float, size: Float, coin: String) {
    context.sendBroadcast(Intent("UPDATE_FLOATER").apply { putExtra("alpha", alpha); putExtra("size", size); putExtra("coin", coin) })
}

enum class Screen { Calculator, AppSettings }

// ==========================================
// 沉浸式主题容器
// ==========================================
@Composable
fun AppTheme(themeMode: Int, useDynamicColor: Boolean, seedColorInt: Int, content: @Composable () -> Unit) {
    val isDark = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useDynamicColor && supportsDynamic -> if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        isDark -> darkColorScheme(primary = Color(seedColorInt))
        else -> lightColorScheme(primary = Color(seedColorInt))
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        setContent {
            var themeMode by remember { mutableIntStateOf(prefs.getInt("themeMode", 0)) }
            var useDynamicColor by remember { mutableStateOf(prefs.getBoolean("dynamicColor", true)) }
            var seedColorInt by remember { mutableIntStateOf(prefs.getInt("seedColor", 0xFF6750A4.toInt())) }

            AppTheme(themeMode, useDynamicColor, seedColorInt) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainAppScreen(
                        themeMode, { themeMode = it; prefs.edit().putInt("themeMode", it).apply() },
                        useDynamicColor, { useDynamicColor = it; prefs.edit().putBoolean("dynamicColor", it).apply() },
                        seedColorInt, { seedColorInt = it; prefs.edit().putInt("seedColor", it).apply() }
                    )
                }
            }
        }
    }
}

@Composable
fun MainAppScreen(
    themeMode: Int, onThemeModeChange: (Int) -> Unit,
    useDynamicColor: Boolean, onDynamicColorChange: (Boolean) -> Unit,
    seedColorInt: Int, onSeedColorChange: (Int) -> Unit
) {
    var currentScreen by remember { mutableStateOf(Screen.Calculator) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(icon = { Icon(Icons.Default.Home, "") }, label = { Text("计算器") }, selected = currentScreen == Screen.Calculator, onClick = { currentScreen = Screen.Calculator })
                NavigationBarItem(icon = { Icon(Icons.Default.Settings, "") }, label = { Text("设置") }, selected = currentScreen == Screen.AppSettings, onClick = { currentScreen = Screen.AppSettings })
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                Screen.Calculator -> CalculatorScreen()
                Screen.AppSettings -> SettingsScreen(themeMode, onThemeModeChange, useDynamicColor, onDynamicColorChange, seedColorInt, onSeedColorChange)
            }
        }
    }
}

// ==========================================
// 页面 1：收益计算器
// ==========================================
@Composable
fun CalculatorScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val gson = Gson()

    var allCoins by remember { mutableStateOf(loadAllCoins(prefs, gson)) }

    var principal by remember { mutableStateOf("100") }
    var rolls by remember { mutableStateOf("3") }
    var result by remember { mutableStateOf(0.0) }
    var selectedCoin by remember { mutableStateOf(allCoins.firstOrNull()?.name ?: "") }
    var selectedTime by remember { mutableStateOf(5) }

    var floaterAlpha by remember { mutableStateOf(0.8f) }
    var floaterSize by remember { mutableStateOf(16f) }
    var isServiceRunning by remember { mutableStateOf(false) }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var coinToEdit by remember { mutableStateOf<CoinData?>(null) }
    var coinMenuExpandedFor by remember { mutableStateOf<CoinData?>(null) }

    val currentRate = allCoins.find { it.name == selectedCoin }?.odds?.get(selectedTime) ?: 0.0

    Column(modifier = Modifier.padding(16.dp)) {
        Text("滚仓收益计算", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            allCoins.forEach { coin ->
                Box {
                    CoinOptionButton(
                        text = coin.name,
                        isSelected = selectedCoin == coin.name,
                        onClick = {
                            selectedCoin = coin.name
                            sendUpdateBroadcast(context, floaterAlpha, floaterSize, selectedCoin)
                        },
                        onLongClick = { coinMenuExpandedFor = coin }
                    )

                    DropdownMenu(
                        expanded = coinMenuExpandedFor == coin,
                        onDismissRequest = { coinMenuExpandedFor = null }
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑赔率") },
                            onClick = {
                                coinToEdit = coin
                                coinMenuExpandedFor = null
                                showAddEditDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除币种", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                allCoins = allCoins.filter { it.name != coin.name }
                                saveAllCoins(prefs, gson, allCoins)
                                if (selectedCoin == coin.name) {
                                    selectedCoin = allCoins.firstOrNull()?.name ?: ""
                                }
                                sendUpdateBroadcast(context, floaterAlpha, floaterSize, selectedCoin)
                                context.sendBroadcast(Intent("RELOAD_COINS"))
                                coinMenuExpandedFor = null
                            }
                        )
                    }
                }
            }
            IconButton(onClick = {
                coinToEdit = null
                showAddEditDialog = true
            }) {
                Icon(Icons.Default.Edit, contentDescription = "添加币种")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30, 60).forEach { time ->
                CoinOptionButton("$time", selectedTime == time, { selectedTime = time }, {})
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = principal, onValueChange = { principal = it }, label = { Text("本金 (U)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(value = rolls, onValueChange = { rolls = it }, label = { Text("次数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val p = principal.toDoubleOrNull() ?: 0.0
                val r = rolls.toIntOrNull() ?: 0
                result = calculateProfit(p, currentRate, r)
            }, modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("开始计算 (当前赔率: ${String.format("%.1f", currentRate * 100)}% 预估: ${String.format("%.2f U", result)})")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text("悬浮窗控制", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("背景透明", modifier = Modifier.width(60.dp))
            Slider(value = floaterAlpha, onValueChange = { floaterAlpha = it; sendUpdateBroadcast(context, floaterAlpha, floaterSize, selectedCoin) }, valueRange = 0.0f..1f, modifier = Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号大小", modifier = Modifier.width(60.dp))
            Slider(value = floaterSize, onValueChange = { floaterSize = it; sendUpdateBroadcast(context, floaterAlpha, floaterSize, selectedCoin) }, valueRange = 10f..24f, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else {
                    val serviceIntent = Intent(context, FloatingService::class.java)
                    if (isServiceRunning) {
                        context.stopService(serviceIntent)
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    }
                    isServiceRunning = !isServiceRunning
                }
            }, modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(if (isServiceRunning) "关闭悬浮窗" else "开启悬浮窗")
        }
    }

    if (showAddEditDialog) {
        var newCoinName by remember { mutableStateOf("") }
        var rate5 by remember { mutableStateOf("") }
        var rate10 by remember { mutableStateOf("") }
        var rate15 by remember { mutableStateOf("") }
        var rate30 by remember { mutableStateOf("") }
        var rate60 by remember { mutableStateOf("") }

        LaunchedEffect(showAddEditDialog, coinToEdit) {
            if (showAddEditDialog) {
                newCoinName = coinToEdit?.name ?: ""
                val formatRate = { rate: Double? -> rate?.times(100)?.let { if (it == 0.0) "" else if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "" }
                rate5 = formatRate(coinToEdit?.odds?.get(5))
                rate10 = formatRate(coinToEdit?.odds?.get(10))
                rate15 = formatRate(coinToEdit?.odds?.get(15))
                rate30 = formatRate(coinToEdit?.odds?.get(30))
                rate60 = formatRate(coinToEdit?.odds?.get(60))
            }
        }

        AlertDialog(
            onDismissRequest = { showAddEditDialog = false },
            title = { Text(if (coinToEdit == null) "添加自定义币种" else "编辑赔率") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newCoinName,
                        onValueChange = { newCoinName = it.uppercase() },
                        label = { Text("币种名称 (如 SOL)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = coinToEdit == null
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = rate5, onValueChange = { rate5 = it }, label = { Text("5分赔率(%)") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = rate10, onValueChange = { rate10 = it }, label = { Text("10分(%)") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = rate15, onValueChange = { rate15 = it }, label = { Text("15分(%)") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = rate30, onValueChange = { rate30 = it }, label = { Text("30分(%)") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    OutlinedTextField(value = rate60, onValueChange = { rate60 = it }, label = { Text("60分(%)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newCoinName.isNotBlank()) {
                        val newCoin = CoinData(
                            name = newCoinName,
                            odds = mapOf(
                                5 to (rate5.toDoubleOrNull() ?: 0.0) / 100.0,
                                10 to (rate10.toDoubleOrNull() ?: 0.0) / 100.0,
                                15 to (rate15.toDoubleOrNull() ?: 0.0) / 100.0,
                                30 to (rate30.toDoubleOrNull() ?: 0.0) / 100.0,
                                60 to (rate60.toDoubleOrNull() ?: 0.0) / 100.0
                            )
                        )

                        if (coinToEdit != null) {
                            allCoins = allCoins.map { if (it.name == coinToEdit!!.name) newCoin else it }
                        } else {
                            allCoins = allCoins.filter { it.name != newCoinName } + newCoin
                        }

                        saveAllCoins(prefs, gson, allCoins)
                        selectedCoin = newCoin.name
                        sendUpdateBroadcast(context, floaterAlpha, floaterSize, selectedCoin)
                        context.sendBroadcast(Intent("RELOAD_COINS"))

                        showAddEditDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { OutlinedButton(onClick = { showAddEditDialog = false }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoinOptionButton(text: String, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        border = if (!isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Text(text = text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SettingsScreen(
    themeMode: Int, onThemeModeChange: (Int) -> Unit,
    useDynamicColor: Boolean, onDynamicColorChange: (Boolean) -> Unit,
    seedColorInt: Int, onSeedColorChange: (Int) -> Unit
) {
    var showThemeDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        ListItem(
            headlineContent = { Text("外观与主题") },
            supportingContent = { Text("切换深浅色、动态取色及主色调") },
            modifier = Modifier.clickable { showThemeDialog = true }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("关于本应用") },
            supportingContent = { Text("v1.0.0 - 专注辅助交易") }
        )
        HorizontalDivider()
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("外观与主题") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("外观模式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        RadioButton(selected = themeMode == 0, onClick = { onThemeModeChange(0) }); Text("跟随系统", modifier = Modifier.clickable { onThemeModeChange(0) }.padding(end = 12.dp))
                        RadioButton(selected = themeMode == 1, onClick = { onThemeModeChange(1) }); Text("浅色", modifier = Modifier.clickable { onThemeModeChange(1) }.padding(end = 12.dp))
                        RadioButton(selected = themeMode == 2, onClick = { onThemeModeChange(2) }); Text("深色", modifier = Modifier.clickable { onThemeModeChange(2) })
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    val canDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("动态取色 (Material You)", style = MaterialTheme.typography.titleMedium)
                            Text(if (canDynamic) "自动提取壁纸色系" else "安卓版本过低", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = useDynamicColor, onCheckedChange = onDynamicColorChange, enabled = canDynamic)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("自定义预设颜色", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf(0xFF6750A4.toInt(), 0xFF0061A4.toInt(), 0xFF386A20.toInt(), 0xFFA23F16.toInt()).forEach { colorInt ->
                            val isSelected = (!useDynamicColor || !canDynamic) && seedColorInt == colorInt
                            Box(modifier = Modifier.size(45.dp).clip(CircleShape).background(Color(colorInt)).border(if (isSelected) 3.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape).clickable { if (useDynamicColor) onDynamicColorChange(false); onSeedColorChange(colorInt) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("完成") } }
        )
    }
}