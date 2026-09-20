package com.dhrashta.x.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dhrashta.x.ui.components.AppIdentity
import com.dhrashta.x.ui.components.Chevron
import com.dhrashta.x.ui.components.ChevronDirection
import com.dhrashta.x.ui.components.ClickableRow
import com.dhrashta.x.ui.components.PrimaryAction
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.components.SecondaryAction
import com.dhrashta.x.ui.theme.Amber
import com.dhrashta.x.ui.theme.AmberSoft
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.Ink
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk

@Composable
fun RiskDetailsScreen(
    onBack: () -> Unit,
    onPauseInternet: () -> Unit,
    onReviewPermissions: () -> Unit,
    onUninstall: () -> Unit,
    onListen: () -> Unit,
) {
    var evidenceExpanded by rememberSaveable { mutableStateOf(true) }
    Column(
        Modifier.fillMaxSize().background(Canvas).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
    ) {
        ScreenHeader("Risk details", onBack)
        Spacer(Modifier.height(12.dp))
        AppIdentity()
        Text(
            "Installed outside Play Store",
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 64.dp, top = 4.dp),
        )
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.fillMaxWidth().background(AmberSoft, RoundedCornerShape(8.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.size(22.dp).background(Amber, CircleShape), contentAlignment = Alignment.Center) {
                Text("!", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
            }
            Text("Possible banking-malware behaviour", color = Amber, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(28.dp))
        Text("Review this app", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Some activity needs your attention. Review the evidence and choose what to do.",
            color = MutedInk,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(22.dp))
        QuietCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                EvidenceItem("Repeated server connections", "Connections repeat at regular intervals.")
                HorizontalDivider(color = Line)
                EvidenceItem("Accessibility access enabled", "Sensitive access granted.")
                HorizontalDivider(color = Line)
                EvidenceItem("Installed outside Play Store", "Source alone does not prove risk.")
            }
        }
        Text(
            "These signs do not confirm malware.",
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(10.dp))
        ClickableRow(onListen) {
            LineIcon(LineIconType.Speaker, Modifier.size(22.dp))
            Text("Listen to explanation", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Chevron()
        }
        HorizontalDivider(color = Line)
        ClickableRow({ evidenceExpanded = !evidenceExpanded }) {
            Text("View evidence", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chevron(if (evidenceExpanded) ChevronDirection.Down else ChevronDirection.Right)
        }
        if (evidenceExpanded) {
            Text(
                "Connections were observed at regular intervals while accessibility access was enabled. All analysis was performed on this device.",
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        PrimaryAction("Pause internet", onPauseInternet)
        Spacer(Modifier.height(10.dp))
        SecondaryAction("Review permissions", onReviewPermissions)
        TextButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Uninstall app", color = Ink, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            LineIcon(LineIconType.Back, Modifier.size(22.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun EvidenceItem(title: String, detail: String) {
    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(7.dp).background(Forest, CircleShape))
        Column(Modifier.padding(start = 11.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
