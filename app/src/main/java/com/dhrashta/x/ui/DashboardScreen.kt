package com.dhrashta.x.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhrashta.x.R
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.RiskBadge
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.components.SectionTitle
import com.dhrashta.x.ui.theme.Amber
import com.dhrashta.x.ui.theme.Accent
import com.dhrashta.x.ui.theme.AccentSoft
import com.dhrashta.x.ui.theme.Canvas as CanvasColor
import com.dhrashta.x.ui.theme.Danger
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Info
import com.dhrashta.x.ui.theme.Ink
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk
import com.dhrashta.x.ui.theme.Surface

@Composable
fun DashboardScreen(
    apps: List<AppRisk>?,
    monitoring: Boolean,
    statusMessage: String,
    recent: List<ActivityItem>,
    labels: Map<String, String>,
    onOpenApp: (String) -> Unit,
    onActivity: () -> Unit,
    onSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val attention = apps.orEmpty().filter { it.level >= RiskLevel.Review && !it.trusted }.sortedByDescending { it.score }
    val paused = apps.orEmpty().count { it.paused }
    val listed = apps.orEmpty().filter { risk ->
        (showSystem || !risk.app.isSystem || risk.app.a11yEnabled) &&
            (query.isBlank() || risk.app.label.contains(query, ignoreCase = true) || risk.app.pkg.contains(query, ignoreCase = true))
    }
    Column(Modifier.fillMaxSize().background(CanvasColor)) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = ScreenPadding)) {
            item {
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_shield), contentDescription = null, modifier = Modifier.size(29.dp))
                    Text(
                        stringResource(R.string.app_name),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.4.sp,
                        color = Ink,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                }
                Spacer(Modifier.height(26.dp))
                Text(stringResource(R.string.home_title), color = Ink, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(14.dp))
                QuietCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(9.dp).background(if (monitoring) Forest else Amber, CircleShape))
                            Text(
                                stringResource(if (monitoring) R.string.home_monitoring_active else R.string.home_monitoring_starting),
                                color = if (monitoring) Forest else Amber,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            statusMessage,
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(14.dp))
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetricItem(apps?.count { !it.app.isSystem }?.toString() ?: "–", stringResource(R.string.metric_apps_checked), Ink, Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(40.dp).background(Line))
                    MetricItem(if (apps == null) "–" else attention.size.toString(), stringResource(R.string.metric_to_review), if (attention.isEmpty()) Ink else Amber, Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(40.dp).background(Line))
                    MetricItem(if (apps == null) "–" else paused.toString(), stringResource(R.string.metric_paused), if (paused == 0) Ink else Info, Modifier.weight(1f))
                }
                Spacer(Modifier.height(34.dp))
                SectionTitle(stringResource(R.string.home_needs_attention))
                Spacer(Modifier.height(13.dp))
            }
            when {
                apps == null -> item { LoadingCard(stringResource(R.string.home_loading_apps)) }
                attention.isEmpty() -> item {
                    QuietCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).background(ForestSoft, CircleShape), contentAlignment = Alignment.Center) {
                                LineIcon(LineIconType.Check, Modifier.size(20.dp), Forest)
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(stringResource(R.string.home_all_clear), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.home_all_clear_detail), color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                else -> items(attention, key = { "attention-${it.app.pkg}" }) { risk ->
                    AttentionCard(risk) { onOpenApp(risk.app.pkg) }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.home_recent_activity), Modifier.weight(1f))
                    if (recent.isNotEmpty()) {
                        Text(
                            stringResource(R.string.home_see_all),
                            color = Accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onActivity).padding(8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(13.dp))
                if (recent.isEmpty()) {
                    QuietCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            LineIcon(LineIconType.Activity, Modifier.size(22.dp), MutedInk)
                            Text(
                                stringResource(R.string.home_no_activity),
                                color = MutedInk,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
            items(recent.take(3), key = { "recent-${it.key}" }) { item ->
                ActivityRow(item, labels, onOpenApp)
                HorizontalDivider(color = Line)
            }
            item {
                Spacer(Modifier.height(24.dp))
                SectionTitle(if (apps == null) stringResource(R.string.home_installed_apps) else stringResource(R.string.home_installed_apps_count, listed.size))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.home_search_apps)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp, bottom = 10.dp)) {
                    FilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        label = { Text(stringResource(R.string.home_show_system)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentSoft, selectedLabelColor = Accent),
                    )
                }
            }
            if (apps != null && listed.isEmpty()) {
                item { Text(stringResource(R.string.home_no_match), color = MutedInk, modifier = Modifier.padding(vertical = 12.dp)) }
            }
            items(listed, key = { "app-${it.app.pkg}" }) { risk ->
                AppRow(risk) { onOpenApp(risk.app.pkg) }
                HorizontalDivider(Modifier.padding(start = 52.dp), color = Line)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        BottomNavigation(active = BottomDestination.Home, onHome = {}, onActivity = onActivity, onSettings = onSettings)
    }
}

@Composable
private fun AttentionCard(risk: AppRisk, onClick: () -> Unit) {
    QuietCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(risk.app.pkg, risk.app.label, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(risk.app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        risk.evidence.firstOrNull()?.title ?: stringResource(R.string.score_short, risk.score),
                        color = MutedInk,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LineIcon(LineIconType.Chevron, Modifier.size(19.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RiskBadge(risk.level, risk.trusted, risk.paused)
                Text(
                    stringResource(R.string.score_short, risk.score),
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            if (risk.evidence.size > 1) {
                Text(
                    risk.evidence.drop(1).take(2).joinToString(" · ") { it.title },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun AppRow(risk: AppRisk, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(risk.app.pkg, risk.app.label, Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(risk.app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sourceText(LocalContext.current.resources, risk.app),
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusDot(risk)
    }
}

@Composable
private fun StatusDot(risk: AppRisk) {
    val color = when {
        risk.paused -> Info
        risk.trusted -> Forest
        risk.level >= RiskLevel.High -> Danger
        risk.level == RiskLevel.Review -> Amber
        else -> Forest
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

/** Where an app came from, in plain words. */
fun sourceText(res: Resources, app: InstalledApp): String = when {
    app.isSystem -> res.getString(R.string.source_preinstalled)
    !app.sideloaded -> res.getString(R.string.source_play_store)
    app.installerLabel != null -> res.getString(R.string.source_installed_by, app.installerLabel)
    else -> res.getString(R.string.source_outside_play)
}

@Composable
fun LoadingCard(text: String) {
    QuietCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
            Text(text, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun MetricItem(value: String, label: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
        Text(label, color = MutedInk, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

enum class BottomDestination { Home, Activity, Settings }

@Composable
fun BottomNavigation(active: BottomDestination, onHome: () -> Unit, onActivity: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Surface)) {
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
            BottomItem(stringResource(R.string.nav_home), LineIconType.Home, active == BottomDestination.Home, onHome)
            BottomItem(stringResource(R.string.nav_activity), LineIconType.Activity, active == BottomDestination.Activity, onActivity)
            BottomItem(stringResource(R.string.nav_settings), LineIconType.Settings, active == BottomDestination.Settings, onSettings)
        }
    }
}

@Composable
private fun RowScope.BottomItem(label: String, icon: LineIconType, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(22.dp).height(2.dp).background(if (selected) Accent else Color.Transparent, RoundedCornerShape(2.dp)))
        LineIcon(icon, Modifier.size(22.dp), if (selected) Accent else MutedInk)
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Accent else MutedInk)
    }
}

enum class LineIconType { Home, Activity, Settings, Chevron, Back, Speaker, Check }

private val SettingsGearPath: Path by lazy {
    PathParser().parsePathString(
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"
    ).toPath()
}

@Composable
fun LineIcon(type: LineIconType, modifier: Modifier = Modifier, color: Color = Ink) {
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl &&
        (type == LineIconType.Chevron || type == LineIconType.Back)
    Canvas(modifier) { scale(if (mirror) -1f else 1f, 1f) {
        val stroke = Stroke(width = 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
                val scaleFactor = w / 24f
                val gearStroke = Stroke(width = stroke.width / scaleFactor, cap = StrokeCap.Round, join = StrokeJoin.Round)
                scale(scaleFactor, scaleFactor, pivot = Offset.Zero) {
                    drawCircle(color, 3.2f, Offset(12f, 12f), style = gearStroke)
                    drawPath(SettingsGearPath, color, style = gearStroke)
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
    } }
}
