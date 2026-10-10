package com.dhrashta.x.ui

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhrashta.x.R
import com.dhrashta.x.ui.components.AppIdentity
import com.dhrashta.x.ui.components.Chevron
import com.dhrashta.x.ui.components.ChevronDirection
import com.dhrashta.x.ui.components.ClickableRow
import com.dhrashta.x.ui.components.PrimaryAction
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.components.SecondaryAction
import com.dhrashta.x.ui.components.SectionTitle
import com.dhrashta.x.ui.theme.Amber
import com.dhrashta.x.ui.theme.AmberSoft
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Danger
import com.dhrashta.x.ui.theme.DangerSoft
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Ink
import com.dhrashta.x.ui.theme.Info
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk

/** Plain-language, anti-jargon summary of why [risk] got its score; used on screen and for "Listen". */
fun explanationFor(res: Resources, risk: AppRisk): String {
    if (risk.trusted) return res.getString(R.string.explain_trusted, risk.app.label)
    if (risk.evidence.isEmpty()) return res.getString(R.string.explain_clean, risk.app.label, sourceText(res, risk.app))

    val story = mutableListOf<String>()

    // 1. Threat feed signature match
    if (risk.app.inThreatList) {
        story += res.getString(R.string.narrative_threat_list)
    }

    // 2. Install origin / provenance
    if (risk.app.sideloaded) {
        val source = risk.app.installerLabel
        if (source != null) {
            story += res.getString(R.string.narrative_sideload_source, risk.app.label, source)
        } else {
            story += res.getString(R.string.narrative_sideload_generic, risk.app.label)
        }
    }

    // 3. Combined Sensitive Capabilities
    val hasA11y = risk.app.a11yEnabled
    val readsSms = risk.app.readsSms
    val drawsOverlay = risk.app.requestsOverlay

    when {
        hasA11y && readsSms -> story += res.getString(R.string.narrative_cap_a11y_sms)
        hasA11y && drawsOverlay -> story += res.getString(R.string.narrative_cap_a11y_overlay)
        hasA11y -> story += res.getString(R.string.narrative_cap_a11y_full)
        readsSms -> story += res.getString(R.string.narrative_cap_sms)
        drawsOverlay -> story += res.getString(R.string.narrative_cap_overlay)
    }

    // 4. Deception & Covert Network Activity
    if (risk.evidence.any { it.weight >= 50 && (it.title.contains("canary", ignoreCase = true) || it.title.contains("नकली", ignoreCase = true) || it.title.contains("নকল", ignoreCase = true)) }) {
        story += res.getString(R.string.narrative_canary_leak)
    }
    if (risk.evidence.any { it.title.contains("beacon", ignoreCase = true) || it.title.contains("सर्वर", ignoreCase = true) || it.title.contains("সার্ভার", ignoreCase = true) }) {
        story += res.getString(R.string.narrative_covert_beacon)
    }
    if (!risk.app.hasLauncher && !risk.app.isSystem) {
        story += res.getString(R.string.narrative_hidden_icon)
    }
    if (risk.app.mimicsBrand) {
        story += res.getString(R.string.narrative_mimics_brand)
    }

    // Fallback ensuring no evidence is dropped if no specific narrative rule matched
    if (story.isEmpty()) {
        story += res.getString(
            R.string.explain_findings,
            risk.app.label,
            risk.evidence.joinToString(res.getString(R.string.explain_separator)) { it.title.replaceFirstChar(Char::lowercase) },
        )
    }

    // 5. Actionable Advice
    story += when {
        risk.paused -> res.getString(R.string.narrative_advice_paused)
        risk.level == RiskLevel.Critical -> res.getString(R.string.narrative_advice_critical)
        risk.level >= RiskLevel.Review -> res.getString(R.string.narrative_advice_review)
        else -> res.getString(R.string.narrative_advice_low)
    }

    return story.joinToString(" ")
}

/** Formats a clean, professional incident report for user export and sharing. */
fun formatIncidentReport(res: Resources, risk: AppRisk): String {
    val sb = StringBuilder()
    sb.appendLine("DHRASHTA-X Security Incident Report")
    sb.appendLine("===================================")
    sb.appendLine("App: ${risk.app.label} (${risk.app.pkg})")
    sb.appendLine("Risk Level: ${risk.level.name.uppercase()} (Score: ${risk.score}/100)")
    sb.appendLine("Installation Source: ${sourceText(res, risk.app)}")
    sb.appendLine("Internet Status: ${if (risk.paused) "PAUSED (Contained)" else "Active"}")
    sb.appendLine()
    sb.appendLine("Plain-Language Summary:")
    sb.appendLine(explanationFor(res, risk))
    sb.appendLine()
    if (risk.evidence.isNotEmpty()) {
        sb.appendLine("Observed Findings:")
        risk.evidence.forEach { ev ->
            sb.appendLine("- ${ev.title}: ${ev.detail}")
        }
        sb.appendLine()
    }
    sb.appendLine("Generated locally on-device by DHRASHTA-X on ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
    return sb.toString()
}

@Composable
fun EqualizerAnimation(modifier: Modifier = Modifier, color: Color = Danger) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "equalizer")
    val anim1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "bar1",
    )
    val anim2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "bar2",
    )
    val anim3 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(480, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "bar3",
    )
    Row(
        modifier = modifier.height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight(anim1).background(color, RoundedCornerShape(1.dp)))
        Box(Modifier.width(3.dp).fillMaxHeight(anim2).background(color, RoundedCornerShape(1.dp)))
        Box(Modifier.width(3.dp).fillMaxHeight(anim3).background(color, RoundedCornerShape(1.dp)))
    }
}

@Composable
fun RiskDetailsScreen(
    risk: AppRisk,
    explanation: String?,
    isSpeaking: Boolean = false,
    onBack: () -> Unit,
    onPauseInternet: () -> Unit,
    onOpenPaused: () -> Unit,
    onReviewPermissions: () -> Unit,
    onUninstall: () -> Unit,
    onTrust: (Boolean) -> Unit,
    onListen: (String) -> Unit,
    onAnalyze: () -> Unit,
    onInspect: (Boolean) -> Unit = {},
    liveThreshold: Float = 1f,
    onShareReport: (AppRisk) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    var evidenceExpanded by rememberSaveable(risk.app.pkg) { mutableStateOf(false) }
    val res = LocalContext.current.resources
    val summary = explanation ?: explanationFor(res, risk)

    Column(
        Modifier.fillMaxSize().background(Canvas).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
    ) {
        ScreenHeader(stringResource(R.string.details_title), onBack)
        Spacer(Modifier.height(12.dp))
        AppIdentity(risk.app)
        Text(
            sourceText(res, risk.app),
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 64.dp, top = 4.dp),
        )
        Spacer(Modifier.height(22.dp))
        VerdictBanner(risk)
        Spacer(Modifier.height(20.dp))
        val method = stringResource(if (risk.fullAnalysis) R.string.details_full_analysis else R.string.details_quick_check)
        Text(
            risk.lastReviewed?.let { stringResource(R.string.details_score_line_time, method, risk.score, relativeTime(res, it)) }
                ?: stringResource(R.string.details_score_line, method, risk.score),
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(22.dp))
        SectionTitle(stringResource(R.string.details_what_we_found))
        Spacer(Modifier.height(12.dp))
        QuietCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (risk.evidence.isEmpty()) {
                    EvidenceItem(stringResource(R.string.details_no_warning_title), stringResource(R.string.details_no_warning_detail), Forest)
                }
                risk.evidence.forEachIndexed { index, evidence ->
                    if (index > 0) HorizontalDivider(color = Line)
                    EvidenceItem(evidence.title, evidence.detail, if (evidence.weight >= 20) Danger else if (evidence.weight >= 10) Amber else Info)
                }
            }
        }
        if (risk.evidence.isNotEmpty() && risk.level >= RiskLevel.Review) {
            Text(
                stringResource(R.string.details_not_confirm),
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        ClickableRow({ onListen(summary) }) {
            if (isSpeaking) {
                EqualizerAnimation(modifier = Modifier.size(22.dp), color = Danger)
                Text(
                    stringResource(R.string.details_listening_active),
                    fontWeight = FontWeight.SemiBold,
                    color = Danger,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                Text(
                    stringResource(R.string.action_stop_audio),
                    fontWeight = FontWeight.Bold,
                    color = Danger,
                    modifier = Modifier.padding(end = 4.dp),
                )
            } else {
                LineIcon(LineIconType.Speaker, Modifier.size(22.dp))
                Text(
                    stringResource(R.string.details_listen),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                Chevron()
            }
        }
        HorizontalDivider(color = Line)
        ClickableRow({ onShareReport(risk) }) {
            LineIcon(LineIconType.Share, Modifier.size(22.dp), color = com.dhrashta.x.ui.theme.Accent)
            Text(
                stringResource(R.string.action_share_report),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            Chevron()
        }
        HorizontalDivider(color = Line)
        ClickableRow({ evidenceExpanded = !evidenceExpanded }) {
            Text(stringResource(R.string.details_technical), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chevron(if (evidenceExpanded) ChevronDirection.Down else ChevronDirection.Right)
        }
        if (evidenceExpanded) {
            TechnicalDetails(risk)
        }
        Spacer(Modifier.height(12.dp))
        if (risk.paused) {
            PrimaryAction(stringResource(R.string.action_paused_manage), onOpenPaused)
        } else if (!risk.app.isSystem) {
            PrimaryAction(stringResource(R.string.action_pause_internet), onPauseInternet)
        }
        if (!risk.paused && !risk.app.isSystem) {
            Spacer(Modifier.height(10.dp))
            SecondaryAction(
                stringResource(if (risk.inspecting) R.string.action_inspect_stop else R.string.action_inspect_start),
                onClick = { onInspect(!risk.inspecting) },
            )
            if (risk.inspecting) {
                val live = risk.live
                Text(
                    if (live == null) {
                        stringResource(R.string.live_status_waiting)
                    } else {
                        stringResource(R.string.live_status, "%.2f".format(live.lastProbability), "%.2f".format(liveThreshold), live.windowsScored)
                    },
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SecondaryAction(stringResource(R.string.action_review_permissions), onReviewPermissions)
        if (risk.app.a11yEnabled) {
            Spacer(Modifier.height(10.dp))
            SecondaryAction(stringResource(R.string.action_analyze_again), onAnalyze)
        }
        TextButton(onClick = { onTrust(!risk.trusted) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(stringResource(if (risk.trusted) R.string.action_untrust else R.string.action_trust), color = Ink, fontWeight = FontWeight.SemiBold)
        }
        if (!risk.app.isSystem) {
            TextButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_uninstall), color = Danger, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun VerdictBanner(risk: AppRisk) {
    val (textRes, fg, bg) = when {
        risk.trusted -> Triple(R.string.verdict_trusted, Forest, ForestSoft)
        risk.level == RiskLevel.Critical -> Triple(R.string.verdict_critical, Danger, DangerSoft)
        risk.level == RiskLevel.High -> Triple(R.string.verdict_high, Danger, DangerSoft)
        risk.level == RiskLevel.Review -> Triple(R.string.verdict_review, Amber, AmberSoft)
        risk.evidence.isNotEmpty() -> Triple(R.string.badge_low, Forest, ForestSoft)
        else -> Triple(R.string.level_safe, Forest, ForestSoft)
    }
    val text = stringResource(textRes)
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(22.dp).background(fg, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (risk.level >= RiskLevel.Review && !risk.trusted) "!" else "✓", color = Canvas, fontWeight = FontWeight.Bold)
        }
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${risk.score}", color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TechnicalDetails(risk: AppRisk) {
    val app = risk.app
    val context = LocalContext.current
    val res = context.resources
    Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailLine(stringResource(R.string.tech_package), app.pkg)
        DetailLine(stringResource(R.string.tech_uid), app.uid.toString())
        DetailLine(stringResource(R.string.tech_installer), app.installer ?: stringResource(R.string.tech_unknown))
        DetailLine(
            stringResource(R.string.tech_installed),
            DateUtils.formatDateTime(context, app.firstInstallTime, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
        )
        DetailLine(
            stringResource(R.string.tech_accessibility),
            stringResource(if (!app.a11yEnabled) R.string.tech_a11y_off else if (app.a11yIsTool) R.string.tech_a11y_tool else R.string.tech_a11y_on),
        )
        DetailLine(
            stringResource(R.string.tech_score),
            stringResource(R.string.tech_score_value, risk.score, if (risk.fullAnalysis) "RiskEngine" else stringResource(R.string.details_quick_check)),
        )
        risk.lastReviewed?.let { DetailLine(stringResource(R.string.tech_last_analysed), relativeTime(res, it)) }
        if (risk.engineSignals.isNotEmpty()) {
            DetailLine(stringResource(R.string.tech_engine_signals), risk.engineSignals.joinToString { "${it.id} (${if (it.weight > 0) "+" else ""}${it.weight})" })
        }
        Text(stringResource(R.string.tech_on_device), color = MutedInk, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.38f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.62f))
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        val backLabel = stringResource(R.string.cd_back)
        Box(
            Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            LineIcon(LineIconType.Back, Modifier.size(22.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun EvidenceItem(title: String, detail: String, dot: Color) {
    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).background(dot, CircleShape))
        Column(Modifier.padding(start = 11.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

