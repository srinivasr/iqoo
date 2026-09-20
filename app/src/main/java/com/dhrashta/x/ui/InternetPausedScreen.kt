package com.dhrashta.x.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhrashta.x.ui.components.AppIdentity
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.components.SecondaryAction
import com.dhrashta.x.ui.components.SectionTitle
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Info
import com.dhrashta.x.ui.theme.InfoSoft
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk

@Composable
fun InternetPausedScreen(onBack: () -> Unit, onResumeInternet: () -> Unit, onReviewPermissions: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Canvas).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding)) {
        ScreenHeader("App controls", onBack)
        Spacer(Modifier.height(12.dp))
        AppIdentity()
        Spacer(Modifier.height(32.dp))
        Box(Modifier.size(48.dp).background(ForestSoft, CircleShape), contentAlignment = Alignment.Center) {
            LineIcon(LineIconType.Check, Modifier.size(25.dp), Forest)
        }
        Text("Internet access paused", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        Text("QuickTools connections through DHRASHTA-X are blocked.", color = MutedInk, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 9.dp))
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.fillMaxWidth().background(InfoSoft, RoundedCornerShape(8.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.size(21.dp).background(Info, CircleShape), contentAlignment = Alignment.Center) {
                Text("i", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
            }
            Text("Other apps can still connect.", color = Info, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(30.dp))
        SectionTitle("What to do next")
        Spacer(Modifier.height(12.dp))
        QuietCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                NextStep("1", "Review this app’s permissions")
                HorizontalDivider(color = Line)
                NextStep("2", "Remove it if you do not trust it")
            }
        }
        Text("Pausing internet does not remove the app or its permissions.", color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
        SecondaryAction("Resume internet", onResumeInternet)
        Spacer(Modifier.height(10.dp))
        SecondaryAction("Review permissions", onReviewPermissions)
        Spacer(Modifier.height(30.dp))
        SectionTitle("Activity")
        Spacer(Modifier.height(12.dp))
        QuietCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(Forest, CircleShape))
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Internet access paused", style = MaterialTheme.typography.titleMedium)
                    Text("Paused just now", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NextStep(number: String, text: String) {
    Row(Modifier.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(25.dp).background(ForestSoft, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, color = Forest, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
    }
}
