package com.gridhelper.capture

import android.app.Activity
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.gridhelper.GridHelperApp
import com.gridhelper.core.AnalysisPipeline
import com.gridhelper.core.AssistantState
import com.gridhelper.core.OverlayModel
import com.gridhelper.debug.DebugFrameSaver
import com.gridhelper.overlay.BubbleController
import com.gridhelper.overlay.OverlayController
import com.gridhelper.vision.AnalysisStatus
import com.gridhelper.vision.FrameAnalysis
import com.gridhelper.vision.FrameAnalyzer
import com.gridhelper.vision.FrameSignature
import com.gridhelper.vision.toPixelSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type mediaProjection) that owns the screen capture, the recognition
 * pipeline, the guide overlay and the floating bubble.
 *
 * Pause sources: user (bubble long-press / notification), screen off, landscape orientation.
 * While paused the virtual display has no surface, so nothing is captured or analysed.
 */
class AssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var app: GridHelperApp

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var overlay: OverlayController? = null
    private var bubble: BubbleController? = null
    private var pipeline: AnalysisPipeline? = null
    private var saver: DebugFrameSaver? = null
    private var receiverRegistered = false
    private var running = false

    // Capture-thread state.
    private val analyzer = FrameAnalyzer()
    private val gate = FrameGate()

    @Volatile private var debugMode = false
    /** False while guides are hidden and debug is off: nothing to show, so skip recognition. */
    @Volatile private var analysisWanted = true
    private var noBoardStreak = 0
    @Volatile private var bandTop = FrameSignature.TOP
    @Volatile private var bandBottom = FrameSignature.BOTTOM

    private var userPaused = false
    private var screenOff = false
    private var landscape = false
    private var boardWasVisible = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                if (running) {
                    AssistantState.post("시스템이 화면 캡처를 종료했습니다. 다시 시작해 주세요.")
                    shutdown()
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff = true
                Intent.ACTION_SCREEN_ON -> screenOff = false
                else -> return
            }
            updateCaptureState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = GridHelperApp.from(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAssistant(intent)
            ACTION_TOGGLE_PAUSE -> if (running) setUserPaused(!userPaused)
            ACTION_STOP -> shutdown()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAssistant(intent: Intent) {
        if (running) return
        // Must be in the foreground with type mediaProjection before getMediaProjection() (Android 14+).
        try {
            startForeground(
                AssistantNotification.ID,
                AssistantNotification.build(this, paused = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "startForeground failed", e)
            AssistantState.post("포그라운드 서비스를 시작할 수 없습니다: ${e.message}")
            stopSelf()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.resultData()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                mpm.getMediaProjection(resultCode, data)
            } catch (e: RuntimeException) {
                Log.e(TAG, "getMediaProjection failed", e)
                null
            }
        } else {
            null
        }
        if (mp == null) {
            AssistantState.post("화면 캡처 권한을 얻지 못했습니다.")
            shutdown()
            return
        }
        projection = mp
        // Android 14+: the callback must be registered before createVirtualDisplay().
        mp.registerCallback(projectionCallback, mainHandler)

        val windowContext = DisplayInfo.overlayContext(this)
        val display = DisplayInfo.of(windowContext)
        val pipe = AnalysisPipeline(scope, app.settings, app.shapeStats).also { it.start() }
        pipeline = pipe
        saver = DebugFrameSaver(this, scope)
        try {
            overlay = OverlayController(windowContext).also { it.attach() }
            bubble = BubbleController(
                windowContext,
                display.width,
                display.height,
                onTap = { app.settings.edit { it.copy(guidesVisible = !it.guidesVisible) } },
                onLongPress = { setUserPaused(!userPaused) },
                avoid = ::recognitionRects,
            ).also { it.attach() }
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "overlay not allowed", e)
            AssistantState.post("다른 앱 위에 표시 권한이 없습니다.")
            shutdown()
            return
        } catch (e: SecurityException) {
            Log.e(TAG, "overlay not allowed", e)
            AssistantState.post("다른 앱 위에 표시 권한이 없습니다.")
            shutdown()
            return
        }

        val cap = ScreenCapturer(mp, display.width, display.height, display.densityDpi, ::onFrame)
        capturer = cap
        try {
            cap.start()
        } catch (e: RuntimeException) {
            // e.g. SecurityException when the consent token was already used / revoked.
            Log.e(TAG, "createVirtualDisplay failed", e)
            AssistantState.post("화면 캡처를 시작할 수 없습니다. 다시 시작해 주세요.")
            shutdown()
            return
        }
        running = true
        registerScreenReceiver()
        screenOff = !getSystemService(PowerManager::class.java).isInteractive
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        scope.launch {
            combine(pipe.model, app.settings.flow) { m, s -> m to s }.collect { (m, s) ->
                debugMode = s.debugMode
                val wanted = s.guidesVisible || s.debugMode
                if (wanted && !analysisWanted) gate.reset() // re-analyse the current screen right away
                analysisWanted = wanted
                updateBand(m)
                overlay?.render(m, s, isPaused())
                bubble?.setState(s.guidesVisible, isPaused())
                if (m.boardVisible && !boardWasVisible) bubble?.avoidRecognitionArea()
                boardWasVisible = m.boardVisible
            }
        }
        updateCaptureState()
        AssistantState.post(null)
    }

    /** Capture thread: throttle, change detection, stability check, recognition. */
    private fun onFrame(image: Image) {
        if (!analysisWanted) return
        if (!gate.shouldSample(SystemClock.uptimeMillis())) return
        if (image.width > image.height) return
        val src = image.toPixelSource()
        val decision = gate.offer(FrameSignature.of(src, bandTop, bandBottom))
        if (decision != FrameGate.Decision.ANALYZE) return
        val analysis = analyzer.analyze(src)
        // Not in the game for a while: sample at 1 fps instead of 3 fps to save battery.
        noBoardStreak = if (analysis.status == AnalysisStatus.NO_BOARD) noBoardStreak + 1 else 0
        gate.intervalMillis = if (noBoardStreak >= 3) FrameGate.IDLE_INTERVAL_MS else FrameGate.ACTIVE_INTERVAL_MS
        pipeline?.submit(analysis)
        if (debugMode && isRecognitionFailure(analysis)) saver?.maybeSave(image, analysis)
    }

    private fun isRecognitionFailure(a: FrameAnalysis): Boolean = when (a.status) {
        AnalysisStatus.UNRELIABLE -> true
        // A square dark region was found but a later stage failed: worth inspecting.
        AnalysisStatus.NO_BOARD -> a.candidate != null && a.failure != null &&
            a.failure != "no square dark region" && a.failure != "landscape frame"
        AnalysisStatus.OK -> false
    }

    /** Narrow the change-detection band to board + tray once the board is known. */
    private fun updateBand(m: OverlayModel) {
        val board = m.board
        val h = m.frameHeight
        if (!m.boardVisible || board == null || h <= 0) {
            bandTop = FrameSignature.TOP
            bandBottom = FrameSignature.BOTTOM
            return
        }
        val trayBottom = m.latest?.tray?.area?.bottom ?: board.outer.bottom
        bandTop = (board.outer.top.toFloat() / h - 0.01f).coerceAtLeast(0f)
        bandBottom = (trayBottom.toFloat() / h + 0.005f).coerceIn(bandTop + 0.1f, 1f)
    }

    private fun recognitionRects(): List<Rect> {
        val m = pipeline?.model?.value ?: return emptyList()
        val list = ArrayList<Rect>(2)
        m.board?.outer?.let { list += Rect(it.left, it.top, it.right, it.bottom) }
        m.latest?.tray?.area?.let { list += Rect(it.left, it.top, it.right, it.bottom) }
        return list
    }

    private fun isPaused() = userPaused || screenOff || landscape

    private fun setUserPaused(paused: Boolean) {
        userPaused = paused
        updateCaptureState()
    }

    private fun updateCaptureState() {
        if (!running) return
        val paused = isPaused()
        gate.reset()
        if (paused) {
            capturer?.pause()
            pipeline?.reset()
        } else {
            capturer?.resume()
        }
        AssistantState.setMode(if (paused) AssistantState.Mode.PAUSED else AssistantState.Mode.RUNNING)
        getSystemService(NotificationManager::class.java)
            .notify(AssistantNotification.ID, AssistantNotification.build(this, paused = userPaused))
        val p = pipeline ?: return
        overlay?.render(p.model.value, app.settings.current, paused)
        bubble?.setState(app.settings.current.guidesVisible, paused)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape != landscape) {
            landscape = isLandscape
            updateCaptureState()
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun shutdown() {
        running = false
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: IllegalArgumentException) {
                // not registered
            }
            receiverRegistered = false
        }
        capturer?.release()
        capturer = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        overlay?.detach()
        overlay = null
        bubble?.detach()
        bubble = null
        pipeline?.reset()
        pipeline = null
        scope.coroutineContext.cancelChildren()
        AssistantState.setMode(AssistantState.Mode.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running || projection != null) shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun Intent.resultData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_RESULT_DATA)
        }

    companion object {
        private const val TAG = "AssistantService"
        const val ACTION_START = "com.gridhelper.action.START"
        const val ACTION_TOGGLE_PAUSE = "com.gridhelper.action.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.gridhelper.action.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** Starts the service with a fresh MediaProjection consent (required for every session on Android 14+). */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AssistantService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))
        }
    }
}
