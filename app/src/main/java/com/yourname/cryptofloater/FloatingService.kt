package com.yourname.cryptofloater

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatRoot: LinearLayout
    private lateinit var contentLayout: LinearLayout
    private lateinit var controlLayout: LinearLayout
    private lateinit var actionLayout: LinearLayout

    private lateinit var dotView: View
    private lateinit var dotDrawable: GradientDrawable
    private lateinit var btnToggle: TextView
    private lateinit var btnReset: TextView
    private lateinit var rootDrawable: GradientDrawable
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // 增加心跳保活，防止服务器主动断开静默连接
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private var isCollapsed = false
    private var isSnappedLeft = true
    private var bgAlphaInt = 230
    private var targetCoin = "BTC"
    private var recordedPrice = 0.0
    private var tradeMode = 0

    private var currentPrices = mutableMapOf<String, Double>()
    private var coinTextViews = mutableMapOf<String, TextView>()
    private var allCoinNames = listOf<String>()
    private var currentTextSize = 16f

    private var lastMessageTime = System.currentTimeMillis()
    private var isReconnecting = false // 防抖标志

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "UPDATE_FLOATER") {
                val alpha = intent.getFloatExtra("alpha", 0.9f)
                currentTextSize = intent.getFloatExtra("size", 16f)
                targetCoin = intent.getStringExtra("coin") ?: "BTC"

                bgAlphaInt = (alpha * 255).toInt()
                updateBackground()

                coinTextViews.values.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSize) }
                updateCoinSelectionUI()
            }
            else if (intent?.action == "RELOAD_COINS") {
                buildDynamicCoinViews()
                reconnectWebSocket()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        // --- 新增：服务启动时读取上次保存的透明度和字号 ---
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val savedAlpha = prefs.getFloat("floaterAlpha", 0.8f)
        bgAlphaInt = (savedAlpha * 255).toInt()
        currentTextSize = prefs.getFloat("floaterSize", 16f)

        // 启动前台服务，防止系统在后台挂起网络
        startForegroundServiceNotification()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 30, 30, 30)
        }
        rootDrawable = GradientDrawable().apply {
            cornerRadius = 50f
            setColor(Color.argb(bgAlphaInt, 28, 27, 31))
            setStroke(2, Color.argb(40, 255, 255, 255))
        }
        floatRoot.background = rootDrawable

        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        actionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 15, 0, 0)
        }

        val btnLong = TextView(this).apply {
            text = "多"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 15)
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.parseColor("#4CAF50")) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 }
            setOnClickListener {
                tradeMode = 1
                recordedPrice = currentPrices[targetCoin] ?: 0.0
                updateIndicatorColor()
            }
        }

        val btnShort = TextView(this).apply {
            text = "空"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 15)
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.parseColor("#F44336")) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 10 }
            setOnClickListener {
                tradeMode = 2
                recordedPrice = currentPrices[targetCoin] ?: 0.0
                updateIndicatorColor()
            }
        }

        actionLayout.addView(btnLong)
        actionLayout.addView(btnShort)

        buildDynamicCoinViews()

        controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(20, 0, 20, 0) }
        }

        btnReset = TextView(this).apply {
            text = "重置"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(15, 8, 15, 8)
            background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#555555")) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 }
            setOnClickListener {
                tradeMode = 0; recordedPrice = 0.0; updateIndicatorColor()
            }
        }

        dotDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) }
        dotView = View(this).apply {
            background = dotDrawable
            layoutParams = LinearLayout.LayoutParams(30, 30).apply { bottomMargin = 20 }
        }

        btnToggle = TextView(this).apply {
            text = "◀"
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(10, 10, 10, 10)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                isCollapsed = !isCollapsed
                if (isCollapsed) {
                    val screenWidth = resources.displayMetrics.widthPixels
                    isSnappedLeft = this@FloatingService.layoutParams.x < screenWidth / 2
                    this@FloatingService.layoutParams.x = if (isSnappedLeft) 0 else screenWidth
                }
                refreshLayoutState()
                windowManager.updateViewLayout(floatRoot, this@FloatingService.layoutParams)
            }
        }

        controlLayout.addView(btnReset); controlLayout.addView(dotView); controlLayout.addView(btnToggle)
        floatRoot.addView(contentLayout); floatRoot.addView(controlLayout)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }

        windowManager.addView(floatRoot, layoutParams)

        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f; var isMoved = false
        floatRoot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x; initialY = layoutParams.y; initialTouchX = event.rawX; initialTouchY = event.rawY; isMoved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isMoved = true
                        layoutParams.x = initialX + dx.toInt(); layoutParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(floatRoot, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoved) {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val snapThreshold = screenWidth * 0.25f
                        if (event.rawX < snapThreshold) { isSnappedLeft = true; isCollapsed = true; layoutParams.x = 0 }
                        else if (event.rawX > screenWidth - snapThreshold) { isSnappedLeft = false; isCollapsed = true; layoutParams.x = screenWidth }
                        else { isSnappedLeft = event.rawX < screenWidth / 2; isCollapsed = false }
                        refreshLayoutState()
                        windowManager.updateViewLayout(floatRoot, layoutParams)
                    }
                    true
                }
                else -> false
            }
        }

        val filter = IntentFilter("UPDATE_FLOATER").apply { addAction("RELOAD_COINS") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        initWebSocket()
        startUiUpdaterLoop()
        startWatchdog()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "crypto_floater_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("行情监控运行中")
            .setContentText("保持后台网络连接以实时刷新")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    private fun buildDynamicCoinViews() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val json = prefs.getString("all_coins_v2", null)

        val allCoinsList: List<Map<String, Any>> = if (json != null) {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } else {
            listOf(mapOf("name" to "BTC"), mapOf("name" to "ETH"))
        }

        allCoinNames = allCoinsList.mapNotNull { it["name"] as? String }

        contentLayout.removeAllViews()
        coinTextViews.clear()

        allCoinNames.forEach { coinName ->
            val tv = TextView(this).apply {
                text = "  $coinName: --"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSize)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 5, 0, 5)
                setOnClickListener {
                    targetCoin = coinName
                    updateCoinSelectionUI()
                }
            }
            coinTextViews[coinName] = tv
            contentLayout.addView(tv)
        }

        contentLayout.addView(actionLayout)
        updateCoinSelectionUI()
    }

    private fun updateCoinSelectionUI() {
        coinTextViews.forEach { (coin, tv) ->
            val priceStr = currentPrices[coin]?.toString() ?: "--"
            if (coin == targetCoin) {
                tv.text = "▶ $coin: $priceStr"
                tv.setTextColor(Color.parseColor("#FFD700"))
            } else {
                tv.text = "  $coin: $priceStr"
                tv.setTextColor(Color.WHITE)
            }
        }
    }

    private fun refreshLayoutState() {
        contentLayout.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        btnReset.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        floatRoot.removeAllViews()
        if (isSnappedLeft) {
            floatRoot.addView(contentLayout); floatRoot.addView(controlLayout)
            btnToggle.text = if (isCollapsed) "▶" else "◀"
        } else {
            floatRoot.addView(controlLayout); floatRoot.addView(contentLayout)
            btnToggle.text = if (isCollapsed) "◀" else "▶"
        }
        updateBackground()
    }

    private fun updateBackground() {
        if (isCollapsed) rootDrawable.setColor(Color.argb(bgAlphaInt, 40, 40, 40))
        else rootDrawable.setColor(Color.argb(bgAlphaInt, 28, 27, 31))
    }

    private fun updateIndicatorColor() {
        if (tradeMode != 0 && recordedPrice > 0) {
            val currentTargetPrice = currentPrices[targetCoin] ?: 0.0
            if (tradeMode == 1) {
                if (currentTargetPrice > recordedPrice) dotDrawable.setColor(Color.RED) else dotDrawable.setColor(Color.GREEN)
            } else if (tradeMode == 2) {
                if (currentTargetPrice < recordedPrice) dotDrawable.setColor(Color.RED) else dotDrawable.setColor(Color.GREEN)
            }
        } else {
            dotDrawable.setColor(Color.GRAY)
        }
    }

    private fun initWebSocket() {
        val request = Request.Builder().url("wss://fapi.hibt0.com/v2/ws").build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                allCoinNames.forEach { coin ->
                    val topic = "${coin.lowercase()}_usdt.ticker"
                    webSocket.send("{\"event\":\"sub\",\"topic\":\"$topic\"}")
                }
                lastMessageTime = System.currentTimeMillis()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("type") && json.has("data")) {
                        val type = json.getString("type")
                        val data = json.getJSONObject("data")
                        if (data.has("lastPrice")) {
                            val price = data.getString("lastPrice").toDoubleOrNull() ?: 0.0
                            val coinName = type.substringBefore("_").uppercase()
                            currentPrices[coinName] = price
                            lastMessageTime = System.currentTimeMillis()
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                reconnectWebSocket()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                reconnectWebSocket()
            }
        }
        webSocket = client.newWebSocket(request, listener)
    }

    private fun startUiUpdaterLoop() {
        scope.launch {
            while (isActive) {
                delay(1000)
                withContext(Dispatchers.Main) {
                    updateCoinSelectionUI()
                    updateIndicatorColor()
                }
            }
        }
    }

    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(5000)
                if (System.currentTimeMillis() - lastMessageTime > 15000) {
                    reconnectWebSocket()
                }
            }
        }
    }

    private fun reconnectWebSocket() {
        if (isReconnecting) return
        isReconnecting = true
        scope.launch {
            webSocket?.cancel()
            delay(3000) // 延迟重连，防止死循环导致栈溢出
            initWebSocket()
            isReconnecting = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        unregisterReceiver(updateReceiver)
        webSocket?.cancel()
        if (::floatRoot.isInitialized) windowManager.removeView(floatRoot)
    }
}