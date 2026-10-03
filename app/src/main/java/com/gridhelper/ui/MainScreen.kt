package com.gridhelper.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gridhelper.GridHelperApp
import com.gridhelper.capture.AssistantService
import com.gridhelper.core.AppSettings
import com.gridhelper.core.AssistantState
import com.gridhelper.solver.EvalWeights
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(resumeTick: Int, onOpenStaticTest: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { GridHelperApp.from(context) }
    val settings by app.settings.flow.collectAsState()
    val mode by AssistantState.mode.collectAsState()
    val message by AssistantState.message.collectAsState()
    var overlayGranted by remember { mutableStateOf(Permissions.canDrawOverlays(context)) }
    var notificationsGranted by remember { mutableStateOf(Permissions.notificationsGranted(context)) }
    var statsSets by remember { mutableIntStateOf(app.shapeStats.totalSets()) }

    LaunchedEffect(resumeTick) {
        overlayGranted = Permissions.canDrawOverlays(context)
        notificationsGranted = Permissions.notificationsGranted(context)
        statsSets = app.shapeStats.totalSets()
    }

    // Step 3: MediaProjection consent (asked for every session; required on Android 14+).
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            AssistantService.start(context, result.resultCode, data)
        } else {
            AssistantState.post("화면 캡처 동의가 취소되었습니다.")
        }
    }
    val requestProjection = {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        // Android 14+: only offer "entire screen" (single-app capture would break the 1:1 coordinates).
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
    // Step 2: notification permission (Android 13+). Optional: the flow continues either way.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
        requestProjection()
    }
    // Step 1: overlay permission.
    val startFlow = {
        when {
            !Permissions.canDrawOverlays(context) -> Permissions.openOverlaySettings(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.notificationsGranted(context) ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> requestProjection()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("GridHelper") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(
                mode = mode,
                message = message,
                overlayGranted = overlayGranted,
                onStart = { startFlow() },
                onStop = { AssistantService.stop(context) },
                onTogglePause = {
                    context.startService(Intent(context, AssistantService::class.java).setAction(AssistantService.ACTION_TOGGLE_PAUSE))
                },
            )
            PermissionCard(
                overlayGranted = overlayGranted,
                notificationsGranted = notificationsGranted,
                onOverlay = { Permissions.openOverlaySettings(context) },
                onNotifications = { Permissions.openNotificationSettings(context) },
            )
            DisplayCard(settings = settings, onChange = { t -> app.settings.edit(t) })
            WeightsCard(weights = settings.weights, onChange = { w -> app.settings.edit { it.copy(weights = w) } })
            ToolsCard(
                debugMode = settings.debugMode,
                statsSets = statsSets,
                debugDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "failed_frames").absolutePath,
                onOpenStaticTest = onOpenStaticTest,
                onClearStats = {
                    app.shapeStats.clear()
                    statsSets = 0
                },
            )
            Text(
                "자동 터치·자동 플레이 기능 없음 · 네트워크 통신 없음 · 모든 분석은 기기 안에서만 수행됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusCard(
    mode: AssistantState.Mode,
    message: String?,
    overlayGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
) {
    SectionCard("실행") {
        val label = when (mode) {
            AssistantState.Mode.STOPPED -> "정지됨"
            AssistantState.Mode.RUNNING -> "실행 중 — 게임 화면에서 추천 배치를 표시합니다"
            AssistantState.Mode.PAUSED -> "일시정지됨 (버블 길게 누르기 또는 알림에서 재개)"
        }
        Text(label)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == AssistantState.Mode.STOPPED) {
                Button(onClick = onStart) { Text(if (overlayGranted) "시작" else "권한 설정 후 시작") }
            } else {
                Button(onClick = onStop) { Text("정지") }
                OutlinedButton(onClick = onTogglePause) {
                    Text(if (mode == AssistantState.Mode.PAUSED) "재개" else "일시정지")
                }
            }
        }
        Text(
            "시작 순서: ① 다른 앱 위에 표시 허용 → ② 알림 허용(Android 13+) → ③ 화면 캡처 동의(매 실행마다)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionCard(
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    onOverlay: () -> Unit,
    onNotifications: () -> Unit,
) {
    SectionCard("권한") {
        PermissionRow("다른 앱 위에 표시", overlayGranted, onOverlay)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow("알림 (상시 알림의 일시정지/종료 버튼)", notificationsGranted, onNotifications)
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (granted) "✅ " else "⚠️ ") + label,
            modifier = Modifier.weight(1f),
        )
        if (!granted) TextButton(onClick = onClick) { Text("설정") }
    }
}

@Composable
private fun DisplayCard(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard("표시") {
        LabeledSlider(
            label = "오버레이 투명도",
            value = settings.overlayOpacity,
            range = AppSettings.MIN_OPACITY..AppSettings.MAX_OPACITY,
            format = { "${(it * 100).toInt()}%" },
            onCommit = { v -> onChange { it.copy(overlayOpacity = v) } },
        )
        SwitchRow("추천 표시 (버블 탭과 동일)", settings.guidesVisible) { v -> onChange { it.copy(guidesVisible = v) } }
        SwitchRow("Monte Carlo 1수 앞보기 (다음 세트 200회 샘플링)", settings.monteCarlo) { v -> onChange { it.copy(monteCarlo = v) } }
        SwitchRow("디버그 모드 (격자·판정 표시, 실패 프레임 PNG 저장)", settings.debugMode) { v -> onChange { it.copy(debugMode = v) } }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Slider that keeps its own state while dragging and commits when released. */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(format(current), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current) },
            valueRange = range,
        )
    }
}

@Composable
private fun WeightsCard(weights: EvalWeights, onChange: (EvalWeights) -> Unit) {
    SectionCard("평가 가중치") {
        val fmt: (Float) -> String = { String.format(Locale.US, "%.1f", it) }
        LabeledSlider("줄 클리어 (줄당)", weights.clearLine.toFloat(), 0f..30f, fmt) { onChange(weights.copy(clearLine = it.toDouble())) }
        LabeledSlider("동시 2줄 이상 보너스", weights.multiClear.toFloat(), 0f..40f, fmt) { onChange(weights.copy(multiClear = it.toDouble())) }
        LabeledSlider("빈칸 수 (칸당)", weights.emptyCell.toFloat(), 0f..3f, fmt) { onChange(weights.copy(emptyCell = it.toDouble())) }
        LabeledSlider("고립 구멍 패널티", weights.hole.toFloat(), 0f..30f, fmt) { onChange(weights.copy(hole = it.toDouble())) }
        LabeledSlider("3×3 배치 가능 보너스", weights.fit3x3.toFloat(), 0f..20f, fmt) { onChange(weights.copy(fit3x3 = it.toDouble())) }
        LabeledSlider("1×5 배치 가능 보너스", weights.fit1x5.toFloat(), 0f..20f, fmt) { onChange(weights.copy(fit1x5 = it.toDouble())) }
        LabeledSlider("5×1 배치 가능 보너스", weights.fit5x1.toFloat(), 0f..20f, fmt) { onChange(weights.copy(fit5x1 = it.toDouble())) }
        LabeledSlider("블록 라이브러리 적합 비율", weights.libraryFit.toFloat(), 0f..60f, fmt) { onChange(weights.copy(libraryFit = it.toDouble())) }
        LabeledSlider("거칠기 패널티 (경계당)", weights.roughness.toFloat(), 0f..3f, fmt) { onChange(weights.copy(roughness = it.toDouble())) }
        LabeledSlider("Monte Carlo 가중치", weights.monteCarlo.toFloat(), 0f..150f, fmt) { onChange(weights.copy(monteCarlo = it.toDouble())) }
        HorizontalDivider()
        TextButton(onClick = { onChange(EvalWeights.DEFAULT) }) { Text("기본값으로 되돌리기") }
    }
}

@Composable
private fun ToolsCard(
    debugMode: Boolean,
    statsSets: Int,
    debugDir: String,
    onOpenStaticTest: () -> Unit,
    onClearStats: () -> Unit,
) {
    SectionCard("도구") {
        Button(onClick = onOpenStaticTest) { Text("정적 이미지 테스트 (갤러리 스크린샷)") }
        Text("학습된 블록 세트: $statsSets 회", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClearStats) { Text("블록 통계 초기화") }
        if (debugMode) {
            Text("실패 프레임 저장 위치:\n$debugDir", style = MaterialTheme.typography.bodySmall)
        }
    }
}
