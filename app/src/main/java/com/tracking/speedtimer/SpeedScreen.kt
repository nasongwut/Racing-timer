package com.tracking.speedtimer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ สีของแอป
private object Palette {
    val bgTop = Color(0xFF0D1526)
    val bgBottom = Color(0xFF05070C)
    val card = Color(0xFF131B2B)
    val cardBorder = Color(0xFF223049)
    val track = Color(0xFF1E2A40)
    val text = Color(0xFFF2F5FA)
    val sub = Color(0xFF8A96AD)
    val cyan = Color(0xFF38D6FF)
    val green = Color(0xFF3DDC97)
    val yellow = Color(0xFFFFD166)
    val orange = Color(0xFFFF9F43)
    val red = Color(0xFFFF5470)
    val violet = Color(0xFFA78BFA)
}

private const val GAUGE_MAX_KMH = 200f

@Composable
fun SpeedScreen(
    viewModel: SpeedViewModel,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(s.message) {
        if (s.message != null) {
            delay(2500)
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.bgTop, Palette.bgBottom)))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(s)

        when {
            !s.hasPermission -> AlertCard(
                icon = Icons.Rounded.Warning,
                text = "ต้องอนุญาตให้เข้าถึงตำแหน่ง (GPS) เพื่อวัดความเร็ว",
                action = "อนุญาต",
                onClick = onRequestPermission,
            )
            !s.gpsEnabled -> AlertCard(
                icon = Icons.Rounded.Warning,
                text = "GPS ปิดอยู่ เปิดเพื่อเริ่มวัดความเร็ว",
                action = "เปิดการตั้งค่า",
                onClick = onOpenLocationSettings,
            )
        }

        SpeedGauge(speedKmh = s.speedKmh, maxKmh = s.maxSpeedKmh)

        // ---------- สถิติ
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(AppIcons.TrendingUp, Palette.orange, "สูงสุด", fmt1(s.maxSpeedKmh), "กม./ชม.", Modifier.weight(1f))
            StatTile(AppIcons.Speed, Palette.cyan, "เฉลี่ย", fmt1(s.avgSpeedKmh), "กม./ชม.", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val (dist, distUnit) = formatDistance(s.distanceM)
            StatTile(Icons.Rounded.Place, Palette.green, "ระยะทาง", dist, distUnit, Modifier.weight(1f))
            StatTile(
                AppIcons.Bolt, Palette.violet, "แรง G",
                String.format(Locale.US, "%.2f", s.accelG),
                if (s.hasAccelSensor) String.format(Locale.US, "สูงสุด %.2f g", s.maxAccelG) else "ไม่มีเซนเซอร์",
                Modifier.weight(1f),
            )
        }

        StopwatchCard(s, viewModel)
        LaunchCard(s, viewModel)

        s.message?.let {
            Text(
                it,
                color = Palette.yellow,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.yellow.copy(alpha = 0.12f))
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------ ส่วนหัว

@Composable
private fun Header(s: TrackerState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("SPEED TIMER", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("วัดความเร็วจากเซนเซอร์", color = Palette.sub, fontSize = 12.sp)
        }
        val ok = s.hasPermission && s.gpsEnabled && s.gpsFix
        val acc = s.accuracyM ?: 99f
        val color = when {
            !ok -> Palette.orange
            acc <= 5f -> Palette.green
            acc <= 15f -> Palette.yellow
            else -> Palette.orange
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                if (ok) Icons.Rounded.LocationOn else Icons.Rounded.Search,
                contentDescription = null, tint = color, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (ok) buildString {
                    append(s.accuracyM?.let { String.format(Locale.US, "±%.0f ม.", it) } ?: "GPS")
                    append(" · ${s.satellitesUsed}/${s.satellitesTotal} ดวง")
                    if (s.dualFrequency) append(" · L1+L5")
                } else if (s.satellitesTotal > 0) "ค้นหา… ${s.satellitesUsed}/${s.satellitesTotal} ดวง"
                else "กำลังค้นหา…",
                color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AlertCard(icon: ImageVector, text: String, action: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.orange.copy(alpha = 0.12f))
            .border(1.dp, Palette.orange.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.orange)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.orange, contentColor = Color.Black),
        ) { Text(action) }
    }
}

// ------------------------------------------------------------------ เกจความเร็ว

@Composable
private fun SpeedGauge(speedKmh: Float, maxKmh: Float) {
    val animated by animateFloatAsState(targetValue = speedKmh, animationSpec = tween(450), label = "speed")
    val animatedMax by animateFloatAsState(targetValue = maxKmh, animationSpec = tween(450), label = "max")
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.08f)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.065f
            val diameter = size.minDimension - stroke * 2.6f
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)
            val radius = diameter / 2
            val c = Offset(size.width / 2, size.height / 2)
            val fraction = (animated / GAUGE_MAX_KMH).coerceIn(0f, 1f)

            // หมุนให้มุม 0° ของ gradient อยู่ที่จุดเริ่มเกจ (ซ้ายล่าง)
            rotate(degrees = 135f, pivot = c) {
                drawArc(
                    color = Palette.track, startAngle = 0f, sweepAngle = 270f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val brush = Brush.sweepGradient(
                    0f to Palette.cyan, 0.30f to Palette.green, 0.50f to Palette.yellow,
                    0.75f to Palette.red, 1f to Palette.red,
                    center = c,
                )
                // แสงเรือง (ชั้นนอก) + เส้นหลัก
                drawArc(
                    brush = brush, startAngle = 0f, sweepAngle = 270f * fraction, useCenter = false,
                    topLeft = topLeft, size = arcSize, alpha = 0.25f,
                    style = Stroke(stroke * 2.1f, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = brush, startAngle = 0f, sweepAngle = 270f * fraction, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }

            // ขีดสเกล + ตัวเลขทุก 20 กม./ชม.
            for (i in 0..20) {
                val angle = Math.toRadians(135.0 + 270.0 * i / 20)
                val major = i % 2 == 0
                val outer = radius - stroke * 1.0f
                val inner = outer - if (major) stroke * 0.7f else stroke * 0.35f
                drawLine(
                    color = if (major) Palette.sub else Palette.track,
                    start = Offset(c.x + inner * cos(angle).toFloat(), c.y + inner * sin(angle).toFloat()),
                    end = Offset(c.x + outer * cos(angle).toFloat(), c.y + outer * sin(angle).toFloat()),
                    strokeWidth = if (major) 3f else 2f,
                    cap = StrokeCap.Round,
                )
                if (major && i % 4 == 0) {
                    val label = "${(GAUGE_MAX_KMH * i / 20).toInt()}"
                    val layout = measurer.measure(label, labelStyle)
                    val r = inner - stroke * 0.9f
                    drawText(
                        layout,
                        topLeft = Offset(
                            c.x + r * cos(angle).toFloat() - layout.size.width / 2,
                            c.y + r * sin(angle).toFloat() - layout.size.height / 2,
                        ),
                    )
                }
            }

            // จุดบอกความเร็วสูงสุด
            if (animatedMax > 0f) {
                val a = Math.toRadians(135.0 + 270.0 * (animatedMax / GAUGE_MAX_KMH).coerceIn(0f, 1f))
                drawCircle(
                    color = Palette.orange, radius = stroke * 0.32f,
                    center = Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat()),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format(Locale.US, "%.0f", animated),
                color = Palette.text,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text("กม./ชม.", color = Palette.sub, fontSize = 16.sp, letterSpacing = 2.sp)
        }
    }
}

// ------------------------------------------------------------------ การ์ดสถิติ

@Composable
private fun StatTile(
    icon: ImageVector, accent: Color, label: String, value: String, unit: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.card)
            .border(1.dp, Palette.cardBorder, RoundedCornerShape(20.dp))
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = Palette.sub, fontSize = 12.sp)
            Text(value, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(unit, color = Palette.sub, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SectionCard(icon: ImageVector, title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.card)
            .border(1.dp, Palette.cardBorder, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

// ------------------------------------------------------------------ นาฬิกาจับเวลา

@Composable
private fun StopwatchCard(s: TrackerState, vm: SpeedViewModel) {
    SectionCard(AppIcons.Timer, "นาฬิกาจับเวลา", Palette.cyan) {
        AutoStartPanel(s, vm)
        Spacer(Modifier.height(8.dp))
        AutoStopPanel(s, vm)
        Spacer(Modifier.height(10.dp))
        Text(
            text = formatTime(s.elapsedMs),
            color = Palette.text,
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(12.dp))

        val playColor by animateColorAsState(if (s.running) Palette.red else Palette.green, label = "play")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundAction(
                icon = Icons.Rounded.Refresh, label = "รีเซ็ต",
                enabled = !s.running && s.elapsedMs > 0, onClick = vm::resetStopwatch,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    onClick = vm::toggleStopwatch,
                    modifier = Modifier.size(78.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = playColor, contentColor = Color.Black),
                ) {
                    Icon(
                        if (s.running) AppIcons.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (s.running) "หยุด" else "เริ่ม",
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(if (s.running) "หยุด" else "เริ่ม", color = Palette.sub, fontSize = 12.sp)
            }
            RoundAction(icon = AppIcons.Flag, label = "รอบ", enabled = s.running, onClick = vm::lap)
        }

        if (s.laps.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            val best = s.laps.minOf { it.lapMs }
            val worst = s.laps.maxOf { it.lapMs }
            s.laps.forEach { lap ->
                val tag = when {
                    s.laps.size < 2 -> Palette.text
                    lap.lapMs == best -> Palette.green
                    lap.lapMs == worst -> Palette.red
                    else -> Palette.text
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.track.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text("รอบ ${lap.number}", color = Palette.sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(formatTime(lap.lapMs), color = tag, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.2f))
                    Text(formatTime(lap.totalMs), color = Palette.sub, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun AutoStartPanel(s: TrackerState, vm: SpeedViewModel) {
    val waiting = s.autoStartG && !s.running && s.elapsedMs == 0L
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (s.autoStartG) Palette.violet.copy(alpha = 0.12f) else Palette.track.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Bolt, contentDescription = null, tint = Palette.violet, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("เริ่มจับเวลาอัตโนมัติ", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("เริ่มเองเมื่อแรง G ถึงค่าที่ตั้งไว้", color = Palette.sub, fontSize = 12.sp)
            }
            Switch(
                checked = s.autoStartG,
                onCheckedChange = { vm.toggleAutoStart() },
                enabled = s.hasAccelSensor,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Palette.violet,
                    uncheckedTrackColor = Palette.track,
                    uncheckedBorderColor = Palette.cardBorder,
                ),
            )
        }

        if (s.autoStartG) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpeedViewModel.AUTO_START_G.forEach { g ->
                    val selected = s.autoStartThresholdG == g
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Palette.violet else Palette.track)
                            .clickable { vm.setAutoStartThreshold(g) }
                            .padding(vertical = 7.dp),
                    ) {
                        Text(
                            String.format(Locale.US, "%.2f g", g),
                            color = if (selected) Color.Black else Palette.sub,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // แถบแสดงแรง G ปัจจุบันเทียบกับค่าที่ตั้งไว้
            val ratio by animateFloatAsState(
                (s.accelG / s.autoStartThresholdG).coerceIn(0f, 1f), tween(150), label = "gRatio",
            )
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = Palette.violet,
                trackColor = Palette.track,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    waiting -> String.format(
                        Locale.US, "รอแรง G ≥ %.2f g · ตอนนี้ %.2f g", s.autoStartThresholdG, s.accelG,
                    )
                    s.running -> "กำลังจับเวลา"
                    else -> "กดรีเซ็ตเพื่อรอเริ่มอัตโนมัติอีกครั้ง"
                },
                color = if (waiting) Palette.violet else Palette.sub,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun AutoStopPanel(s: TrackerState, vm: SpeedViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (s.autoStopEnabled) Palette.green.copy(alpha = 0.10f) else Palette.track.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Flag, contentDescription = null, tint = Palette.green, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("หยุดจับเวลาอัตโนมัติ", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("หยุดเองเมื่อถึงระยะที่กำหนด", color = Palette.sub, fontSize = 12.sp)
            }
            Switch(
                checked = s.autoStopEnabled,
                onCheckedChange = { vm.toggleAutoStop() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Palette.green,
                    uncheckedTrackColor = Palette.track,
                    uncheckedBorderColor = Palette.cardBorder,
                ),
            )
        }

        if (s.autoStopEnabled) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpeedViewModel.DISTANCE_PRESETS.forEach { m ->
                    val selected = s.autoStopMeters == m
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Palette.green else Palette.track)
                            .clickable { vm.setAutoStopMeters(m) }
                            .padding(vertical = 7.dp),
                    ) {
                        Text(
                            if (m >= 1000) "${m / 1000} กม." else "$m ม.",
                            color = if (selected) Color.Black else Palette.sub,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // กำหนดระยะเอง
            var text by remember(s.autoStopMeters) { mutableStateOf(s.autoStopMeters.toString()) }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v.filter { it.isDigit() }.take(6)
                    text.toIntOrNull()?.let { vm.setAutoStopMeters(it) }
                },
                label = { Text("ระยะ (เมตร)") },
                singleLine = true,
                enabled = !s.running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Palette.text,
                    unfocusedTextColor = Palette.text,
                    focusedBorderColor = Palette.green,
                    unfocusedBorderColor = Palette.cardBorder,
                    focusedLabelColor = Palette.green,
                    unfocusedLabelColor = Palette.sub,
                    cursorColor = Palette.green,
                ),
            )

            // ความคืบหน้าระยะทาง
            Spacer(Modifier.height(8.dp))
            val ratio by animateFloatAsState(
                (s.distanceM / s.autoStopMeters).toFloat().coerceIn(0f, 1f), tween(300), label = "distRatio",
            )
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = Palette.green,
                trackColor = Palette.track,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                String.format(Locale.US, "%.1f / %d ม.", s.distanceM, s.autoStopMeters),
                color = Palette.sub, fontSize = 12.sp,
            )

            s.lastRun?.let { r ->
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.green.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, tint = Palette.green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "${r.meters} ม. ใน ${formatSeconds(r.timeMs)}",
                            color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        )
                        Text(
                            String.format(Locale.US, "ความเร็วปลายทาง %.1f · สูงสุด %.1f กม./ชม.", r.trapSpeedKmh, r.maxSpeedKmh),
                            color = Palette.sub, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(58.dp),
            border = BorderStroke(1.5.dp, if (enabled) Palette.cyan.copy(alpha = 0.6f) else Palette.cardBorder),
            colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = Palette.cyan, disabledContentColor = Palette.sub.copy(alpha = 0.4f)),
        ) { Icon(icon, contentDescription = label) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Palette.sub, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ จับเวลาออกตัว

@Composable
private fun LaunchCard(s: TrackerState, vm: SpeedViewModel) {
    SectionCard(AppIcons.Speed, "จับเวลาออกตัว 0–${s.targetKmh} กม./ชม.", Palette.orange) {
        // ตัวเลือกความเร็วเป้าหมาย
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.track.copy(alpha = 0.6f))
                .padding(4.dp),
        ) {
            SpeedViewModel.TARGETS.forEach { t ->
                val selected = s.targetKmh == t
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) Brush.horizontalGradient(listOf(Palette.orange, Palette.red))
                            else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        )
                        .clickable { vm.setTarget(t) }
                        .padding(vertical = 9.dp),
                ) {
                    Text(
                        "$t", fontWeight = FontWeight.SemiBold,
                        color = if (selected) Color.Black else Palette.sub,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        val done = s.launch == LaunchState.Done
        Text(
            text = formatSeconds(s.launchResultMs ?: s.launchLiveMs),
            color = if (done) Palette.orange else Palette.text,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            text = when (s.launch) {
                LaunchState.Idle -> "หยุดนิ่ง แล้วกด \"พร้อม\""
                LaunchState.Armed -> "พร้อมแล้ว — ออกตัวได้เลย"
                LaunchState.Timing -> "กำลังจับเวลา…"
                LaunchState.Done -> "ถึง ${s.targetKmh} กม./ชม. แล้ว"
            },
            color = when (s.launch) {
                LaunchState.Armed -> Palette.green
                LaunchState.Timing -> Palette.yellow
                else -> Palette.sub
            },
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // แถบความคืบหน้า (ความเร็วปัจจุบัน / เป้าหมาย)
        val progress = when (s.launch) {
            LaunchState.Done -> 1f
            LaunchState.Timing -> (s.speedKmh / s.targetKmh).coerceIn(0f, 1f)
            else -> 0f
        }
        val animatedProgress by animateFloatAsState(progress, tween(300), label = "progress")
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = Palette.orange,
            trackColor = Palette.track,
        )

        s.bestLaunchMs[s.targetKmh]?.let { best ->
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(CircleShape)
                    .background(Palette.yellow.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = Palette.yellow, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("ดีที่สุด ${formatSeconds(best)}", color = Palette.yellow, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        val armed = s.launch == LaunchState.Armed || s.launch == LaunchState.Timing
        Button(
            onClick = if (armed) vm::cancelLaunch else vm::armLaunch,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (armed) Palette.track else Palette.orange,
                contentColor = if (armed) Palette.text else Color.Black,
            ),
        ) {
            Text(
                when {
                    armed -> "ยกเลิก"
                    done -> "ลองอีกครั้ง"
                    else -> "พร้อม"
                },
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ------------------------------------------------------------------ ฟังก์ชันจัดรูปแบบ

private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)

/** mm:ss.cc หรือ h:mm:ss.cc */
fun formatTime(ms: Long): String {
    val totalCs = ms / 10
    val cs = totalCs % 100
    val totalSec = totalCs / 100
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hr = totalSec / 3600
    return if (hr > 0) String.format(Locale.US, "%d:%02d:%02d.%02d", hr, min, sec, cs)
    else String.format(Locale.US, "%02d:%02d.%02d", min, sec, cs)
}

fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.2f s", ms / 1000.0)

fun formatDistance(m: Double): Pair<String, String> =
    if (m < 1000) String.format(Locale.US, "%.0f", m) to "เมตร"
    else String.format(Locale.US, "%.2f", m / 1000) to "กม."
