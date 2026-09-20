package com.dhrashta.x.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dhrashta.x.data.EvaluationStateStore
import com.dhrashta.x.enforcement.GuidedRecovery

private val Background = Color(0xFFF2F2F3)
private val CardWhite = Color.White
private val PrimaryBlue = Color(0xFF2244D5)
private val PrimaryText = Color(0xFF262728)
private val SecondaryText = Color(0xFF6B6D72)
private val Border = Color(0xFFE4E5E8)
private val Critical = Color(0xFFE5484D)
private val High = Color(0xFFF59E0B)
private val Review = Color(0xFFF5C542)
private val Safe = Color(0xFF22A06B)

@Composable
fun DhrashtaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PrimaryBlue,
            background = Background,
            surface = CardWhite,
            onBackground = PrimaryText,
            onSurface = PrimaryText,
            outline = Border,
            error = Critical,
        ),
        content = content,
    )
}

@Composable
fun DhrashtaScreen(onScan: () -> Unit, onEnableContainment: () -> Unit) {
    val state by EvaluationStateStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val accent = bandColor(state.band)

    Column(
        modifier = Modifier.fillMaxSize().background(Background).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DHRASHTA-X", color = PrimaryBlue, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text("On-Device Accessibility Threat Intelligence", color = SecondaryText)

        AppCard {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(14.dp).background(if (state.monitoring) Safe else Critical, CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(if (state.monitoring) "Protection active" else "Protection unavailable", fontWeight = FontWeight.Bold)
                    Text(state.message, color = SecondaryText, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.evaluating) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        }

        Button(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(20.dp),
            enabled = !state.evaluating,
        ) {
            Text(if (state.evaluating) "Scanning…" else "Scan Now")
        }
        OutlinedButton(
            onClick = onEnableContainment,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("Enable Network Containment")
        }
        OutlinedButton(
            onClick = { GuidedRecovery.openA11ySettings(context) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("Open Accessibility Settings")
        }

        if (state.score != null && state.packageName != null) {
            Text("Latest evaluation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            AppCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.packageName.orEmpty(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Accessibility service evaluation", color = SecondaryText)
                        }
                        Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = 0.14f)) {
                            Text(
                                state.band.orEmpty(),
                                color = accent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(74.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = (state.score ?: 0).coerceAtMost(100) / 100f,
                                modifier = Modifier.fillMaxSize(),
                                color = accent,
                                trackColor = Border,
                                strokeWidth = 8.dp,
                            )
                            Text("${state.score}", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        }
                        Text(
                            state.explanation.orEmpty(),
                            color = SecondaryText,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (state.firedSignals.isNotEmpty()) {
                        Text("Signals", fontWeight = FontWeight.Bold)
                        state.firedSignals.forEach { signal -> Text("• $signal", color = SecondaryText) }
                    }
                    if (state.contained) {
                        Text("Network access paused", color = Safe, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { GuidedRecovery.openAppInfo(context, state.packageName.orEmpty()) },
                            modifier = Modifier.weight(1f),
                        ) { Text("App info") }
                        Button(
                            onClick = { GuidedRecovery.requestUninstall(context, state.packageName.orEmpty()) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Critical),
                        ) { Text("Remove") }
                    }
                }
            }
        } else {
            AppCard {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No evaluation yet", fontWeight = FontWeight.Bold)
                    Text(
                        "Enable an accessibility service for a test app or tap Scan Now.",
                        color = SecondaryText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AppCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) { content() }
}

private fun bandColor(band: String?): Color = when (band) {
    "CRITICAL" -> Critical
    "HIGH" -> High
    "REVIEW" -> Review
    else -> Safe
}
