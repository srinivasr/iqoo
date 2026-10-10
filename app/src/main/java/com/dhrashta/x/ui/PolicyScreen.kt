package com.dhrashta.x.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhrashta.x.R
import com.dhrashta.x.ui.components.PrimaryAction
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.theme.Accent
import com.dhrashta.x.ui.theme.AccentSoft
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Ink
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk
import com.dhrashta.x.ui.theme.Surface

enum class PolicyType { DPDP, STORAGE, CONTAINMENT, DECOY, EXPLAINABILITY }

data class PolicyItem(
    val type: PolicyType,
    val category: String,
    val title: String,
    val guarantee: String,
    val details: String,
)

data class PermissionRationale(
    val permissionName: String,
    val humanTitle: String,
    val statusTag: String,
    val rationale: String,
    val mechanism: String,
)

@Composable
fun PolicyScreen(
    onboarding: Boolean,
    onAccept: () -> Unit,
    onBack: (() -> Unit)?,
) {
    if (onBack != null) BackHandler(onBack = onBack)

    var selectedTab by remember { mutableIntStateOf(0) }
    var userAcknowledged by remember { mutableStateOf(!onboarding) }

    val policies = remember {
        listOf(
            PolicyItem(
                type = PolicyType.DPDP,
                category = "DPDP ACT 2023 · DATA MINIMISATION",
                title = "100% On-Device Processing Guarantee",
                guarantee = "Zero Cloud Telemetry. No personal files, network packets, or device logs ever leave this phone.",
                details = "All risk evaluations, application inspections, and behavioral scoring execute strictly in local memory without communicating with any external servers. Fully compliant with Sections 6 and 7 of India's Digital Personal Data Protection Act (DPDP Act 2023).",
            ),
            PolicyItem(
                type = PolicyType.STORAGE,
                category = "DATA RETENTION · STORAGE LIMITATION",
                title = "Automated 7-Day Storage Purge",
                guarantee = "Diagnostic and event logs are strictly rolling and permanently deleted after 7 days.",
                details = "Security event records and connection metadata are stored strictly in private, sandboxed local application storage. An automated background worker permanently removes records older than 7 days to eliminate persistent storage accumulation.",
            ),
            PolicyItem(
                type = PolicyType.CONTAINMENT,
                category = "TARGETED DEFENSE · SAFETY",
                title = "Surgical Per-Application Network Isolation",
                guarantee = "Isolation targets only the rogue application; general device internet connectivity remains uninterrupted.",
                details = "When critical unauthorized behavior is identified, containment isolates network access strictly for that offending application package. All other applications, phone calls, and internet services continue operating normally without requiring root privileges.",
            ),
            PolicyItem(
                type = PolicyType.DECOY,
                category = "USER CONTROL · ETHICAL TESTING",
                title = "Opt-In Decoy Traps",
                guarantee = "Decoy markers are disabled by default and completely wiped upon toggle.",
                details = "An optional synthetic address book entry ('Security Verification Contact') monitors whether untrusted applications attempt unauthorized background exfiltration. Decoys require explicit user activation and are immediately removed if toggled off in Settings.",
            ),
            PolicyItem(
                type = PolicyType.EXPLAINABILITY,
                category = "TRANSPARENCY · MULTI-LANGUAGE",
                title = "Transparent Behavioral Evidence",
                guarantee = "Every risk evaluation is backed by visible factors with 13 Indian language translations.",
                details = "All threat assessments present concrete, auditable factors—such as hidden launcher icons, unauthorized background overlays, or unsigned certificates—available in English and 12 regional Indian languages with optional voice assistance.",
            ),
        )
    }

    val permissions = remember {
        listOf(
            PermissionRationale(
                permissionName = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                humanTitle = "Continuous System Protection Service",
                statusTag = "SYSTEM SERVICE",
                rationale = "Maintains background security evaluation of newly installed apps and accessibility permission modifications without system termination.",
                mechanism = "Operates an Android Foreground Service with a transparent status notification.",
            ),
            PermissionRationale(
                permissionName = "android.net.VpnService (BIND_VPN_SERVICE)",
                humanTitle = "Local Traffic Isolation & Inspection",
                statusTag = "LOCAL GATEWAY",
                rationale = "Enables on-device packet inspection to detect command-and-control beaconing and isolates rogue apps locally without connecting to external VPN servers.",
                mechanism = "Utilizes Android's local TUN interface; loops traffic strictly inside the device.",
            ),
            PermissionRationale(
                permissionName = "android.permission.PACKAGE_USAGE_STATS",
                humanTitle = "Application Usage & Overlay Verification",
                statusTag = "USAGE ACCESS",
                rationale = "Detects when sensitive banking, payment, and government apps are brought to the foreground, preventing fraudulent background overlays from stealing credentials.",
                mechanism = "Queries standard Android UsageStats events for active window transitions.",
            ),
            PermissionRationale(
                permissionName = "android.permission.QUERY_ALL_PACKAGES",
                humanTitle = "Installed Application Inventory",
                statusTag = "SECURITY AUDIT",
                rationale = "Audits installed packages to verify developer signing certificates against known threat registries, identify sideloaded apps, and detect hidden applications.",
                mechanism = "Inspects package metadata and signatures locally via Android PackageManager.",
            ),
            PermissionRationale(
                permissionName = "android.permission.WRITE_CONTACTS (Optional)",
                humanTitle = "Decoy Marker Placement",
                statusTag = "OPT-IN DEFENSE",
                rationale = "Plants a single synthetic contact marker to trap spyware and trojans attempting unauthorized address book exfiltration.",
                mechanism = "Disabled by default. Toggled explicitly by the user, and immediately deleted upon disabling.",
            ),
            PermissionRationale(
                permissionName = "android.permission.POST_NOTIFICATIONS",
                humanTitle = "Critical Security Alerts",
                statusTag = "NOTIFICATIONS",
                rationale = "Delivers immediate alerts to the user when high-risk behaviors or containment actions require user attention.",
                mechanism = "Dispatches alerts locally via the Android NotificationManager.",
            ),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas),
    ) {
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = ScreenPadding),
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                if (onBack != null) {
                    ScreenHeader(stringResource(R.string.cd_back), onBack)
                    Spacer(Modifier.height(10.dp))
                } else {
                    Spacer(Modifier.height(20.dp))
                }

                // Clean Executive Brand Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface)
                        .border(1.dp, Line, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Official Shield Logo in Clean Container
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .background(AccentSoft, CircleShape)
                                .border(1.dp, Line, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_shield),
                                contentDescription = "DHRASHTA-X Shield Logo",
                                modifier = Modifier.size(42.dp),
                            )
                        }

                        Text(
                            text = "DHRASHTA-X",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = Ink,
                        )

                        Text(
                            text = "Institutional Mobile Security & Privacy Compliance",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MutedInk,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(Modifier.height(4.dp))

                        // Verified Compliance Badge
                        Row(
                            modifier = Modifier
                                .background(ForestSoft, RoundedCornerShape(20.dp))
                                .border(1.dp, Forest.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(6.dp).background(Forest, CircleShape))
                            Text(
                                text = "DPDP Act 2023 Compliant · Zero Cloud Telemetry",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Forest,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Segmented Tabs
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Surface, RoundedCornerShape(12.dp))
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TabPill(
                        label = "Data Protection Policies",
                        count = policies.size,
                        selected = selectedTab == 0,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = 0 },
                    )
                    TabPill(
                        label = "Services & Permissions",
                        count = permissions.size,
                        selected = selectedTab == 1,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = 1 },
                    )
                }

                Spacer(Modifier.height(16.dp))
            }

            if (selectedTab == 0) {
                // Section: Policies
                item {
                    Text(
                        text = "Data Protection & Privacy Architecture",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                    )
                    Text(
                        text = "Auditable security principles and statutory protections presented for compliance evaluation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedInk,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    )
                }

                items(policies.size, key = { "policy-$it" }) { idx ->
                    val item = policies[idx]
                    PolicyCard(item)
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                // Section: Permissions & Services
                item {
                    Text(
                        text = "Requested Android Services & System Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                    )
                    Text(
                        text = "Every privilege requested is strictly bounded to an on-device defensive purpose.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedInk,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    )
                }

                items(permissions.size, key = { "perm-$it" }) { idx ->
                    val item = permissions[idx]
                    PermissionCard(item)
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Acknowledgement Checkbox for Onboarding
            if (onboarding) {
                item {
                    Spacer(Modifier.height(8.dp))
                    QuietCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .clickable { userAcknowledged = !userAcknowledged }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = userAcknowledged,
                                onCheckedChange = { userAcknowledged = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Accent,
                                    uncheckedColor = MutedInk,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "I acknowledge that DHRASHTA-X operates 100% on-device with zero cloud telemetry, and consent to local security monitoring.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }

        // Bottom Action Bar
        Box(
            Modifier
                .fillMaxWidth()
                .background(Surface)
                .border(1.dp, Line)
                .padding(horizontal = ScreenPadding, vertical = 14.dp)
                .navigationBarsPadding(),
        ) {
            if (onboarding) {
                PrimaryAction(
                    text = if (userAcknowledged) "Accept & Continue" else "Acknowledge Policies to Proceed",
                    onClick = {
                        if (userAcknowledged) onAccept()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PrimaryAction(
                    text = "Close & Return to Settings",
                    onClick = { onBack?.invoke() ?: onAccept() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AccentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Accent else MutedInk,
            )
            Box(
                modifier = Modifier
                    .background(
                        if (selected) Accent else Line,
                        CircleShape,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = count.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MutedInk,
                )
            }
        }
    }
}

@Composable
private fun PolicyCard(item: PolicyItem) {
    val (badgeColor, badgeBg) = when (item.type) {
        PolicyType.DPDP -> Forest to ForestSoft
        PolicyType.STORAGE -> Accent to AccentSoft
        PolicyType.CONTAINMENT -> Accent to AccentSoft
        PolicyType.DECOY -> Forest to ForestSoft
        PolicyType.EXPLAINABILITY -> Accent to AccentSoft
    }

    QuietCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Category badge
            Text(
                text = item.category,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = badgeColor,
                letterSpacing = 0.5.sp,
                modifier = Modifier
                    .background(badgeBg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )

            // Guarantee highlight box
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Canvas, RoundedCornerShape(8.dp))
                    .border(1.dp, Line, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    text = item.guarantee,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Forest,
                    lineHeight = 18.sp,
                )
            }

            Text(
                text = item.details,
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionRationale) {
    QuietCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.statusTag,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier
                        .background(AccentSoft, RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )

                Text(
                    text = "ON-DEVICE ONLY",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Forest,
                )
            }

            Text(
                text = item.humanTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )

            Text(
                text = item.permissionName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MutedInk,
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Canvas, RoundedCornerShape(8.dp))
                    .border(1.dp, Line, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Why this is required:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        text = item.rationale,
                        fontSize = 13.sp,
                        color = MutedInk,
                        lineHeight = 18.sp,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(5.dp).background(Accent, CircleShape))
                Text(
                    text = item.mechanism,
                    fontSize = 11.sp,
                    color = MutedInk,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}
