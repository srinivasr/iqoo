package com.dhrashta.x.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhrashta.x.ui.components.AmberLabel
import com.dhrashta.x.ui.components.QuickToolsIcon
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.components.SectionTitle
import com.dhrashta.x.ui.theme.Canvas as CanvasColor
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.Ink
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk
import com.dhrashta.x.ui.theme.Surface

@Composable
fun DashboardScreen(
    onRiskDetails: () -> Unit,
    onActivity: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(CanvasColor)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
        ) {
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("DHRASHTA-X", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(44.dp).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                    LineIcon(LineIconType.Settings, Modifier.size(23.dp))
                }
            }
            Spacer(Modifier.height(26.dp))
            Text("App protection", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(8.dp).background(Forest, CircleShape))
                Text("Monitoring active", color = Forest, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Analysis stays on your phone.",
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("1", "app to review", Modifier.weight(1f))
                MetricCard("0", "apps paused", Modifier.weight(1f))
            }
            Spacer(Modifier.height(34.dp))
            SectionTitle("Needs your attention")
            Spacer(Modifier.height(13.dp))
            QuietCard(Modifier.fillMaxWidth().clickable(onClick = onRiskDetails)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuickToolsIcon(Modifier.size(44.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("QuickTools", style = MaterialTheme.typography.titleMedium)
                            Text("Installed outside Play Store", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                        }
                        LineIcon(LineIconType.Chevron, Modifier.size(19.dp))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
                    AmberLabel("Review recommended")
                    Text(
                        "Repeated connections and sensitive access",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedInk,
                        modifier = Modifier.padding(top = 9.dp),
                    )
                }
            }
            Spacer(Modifier.height(34.dp))
            SectionTitle("Recent activity")
            Spacer(Modifier.height(13.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(LineIconType.Activity, Modifier.size(22.dp), MutedInk)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("QuickTools reviewed", style = MaterialTheme.typography.titleMedium)
                        Text("A few minutes ago", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomNavigation(active = BottomDestination.Home, onHome = {}, onActivity = onActivity, onSettings = onSettings)
    }
}

@Composable
private fun MetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    QuietCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(value, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
            Text(label, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

enum class BottomDestination { Home, Activity, Settings }

@Composable
fun BottomNavigation(active: BottomDestination, onHome: () -> Unit, onActivity: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Surface).padding(top = 9.dp).navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        BottomItem("Home", LineIconType.Home, active == BottomDestination.Home, onHome)
        BottomItem("Activity", LineIconType.Activity, active == BottomDestination.Activity, onActivity)
        BottomItem("Settings", LineIconType.Settings, active == BottomDestination.Settings, onSettings)
    }
}

@Composable
private fun BottomItem(label: String, icon: LineIconType, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        LineIcon(icon, Modifier.size(22.dp), if (selected) Forest else MutedInk)
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Forest else MutedInk)
    }
}

enum class LineIconType { Home, Activity, Settings, Chevron, Back, Speaker, Check }

@Composable
fun LineIcon(type: LineIconType, modifier: Modifier = Modifier, color: Color = Ink) {
    Canvas(modifier) {
        val stroke = Stroke(width = 2.1f, cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        when (type) {
            LineIconType.Home -> {
                val p = Path().apply { moveTo(w * .17f, h * .48f); lineTo(w * .5f, h * .18f); lineTo(w * .83f, h * .48f); moveTo(w * .25f, h * .42f); lineTo(w * .25f, h * .82f); lineTo(w * .75f, h * .82f); lineTo(w * .75f, h * .42f) }
                drawPath(p, color, style = stroke)
            }
            LineIconType.Activity -> {
                val p = Path().apply { moveTo(w * .12f, h * .55f); lineTo(w * .3f, h * .55f); lineTo(w * .42f, h * .27f); lineTo(w * .58f, h * .74f); lineTo(w * .7f, h * .45f); lineTo(w * .88f, h * .45f) }
                drawPath(p, color, style = stroke)
            }
            LineIconType.Settings -> {
                drawCircle(color, w * .17f, Offset(w * .5f, h * .5f), style = stroke)
                repeat(8) { index ->
                    val angle = Math.PI * index / 4
                    drawLine(color, Offset((w * .5f + kotlin.math.cos(angle).toFloat() * w * .28f), (h * .5f + kotlin.math.sin(angle).toFloat() * h * .28f)), Offset((w * .5f + kotlin.math.cos(angle).toFloat() * w * .39f), (h * .5f + kotlin.math.sin(angle).toFloat() * h * .39f)), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
            LineIconType.Chevron -> { drawLine(color, Offset(w * .35f, h * .2f), Offset(w * .65f, h * .5f), strokeWidth = stroke.width, cap = StrokeCap.Round); drawLine(color, Offset(w * .65f, h * .5f), Offset(w * .35f, h * .8f), strokeWidth = stroke.width, cap = StrokeCap.Round) }
            LineIconType.Back -> { drawLine(color, Offset(w * .75f, h * .2f), Offset(w * .3f, h * .5f), strokeWidth = stroke.width, cap = StrokeCap.Round); drawLine(color, Offset(w * .3f, h * .5f), Offset(w * .75f, h * .8f), strokeWidth = stroke.width, cap = StrokeCap.Round) }
            LineIconType.Speaker -> {
                val p = Path().apply { moveTo(w * .15f, h * .42f); lineTo(w * .34f, h * .42f); lineTo(w * .55f, h * .24f); lineTo(w * .55f, h * .76f); lineTo(w * .34f, h * .58f); lineTo(w * .15f, h * .58f); close() }
                drawPath(p, color, style = stroke); drawArc(color, -55f, 110f, false, topLeft = Offset(w * .42f, h * .27f), size = androidx.compose.ui.geometry.Size(w * .38f, h * .46f), style = stroke)
            }
            LineIconType.Check -> { drawLine(color, Offset(w * .2f, h * .52f), Offset(w * .42f, h * .72f), strokeWidth = 2.8f, cap = StrokeCap.Round); drawLine(color, Offset(w * .42f, h * .72f), Offset(w * .82f, h * .27f), strokeWidth = 2.8f, cap = StrokeCap.Round) }
        }
    }
}
