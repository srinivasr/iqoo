package com.dhrashta.x.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhrashta.x.R
import com.dhrashta.x.ui.components.PrimaryAction
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Ink
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk
import com.dhrashta.x.ui.theme.Surface
import com.dhrashta.x.ui.theme.SurfaceRaised

enum class PolicyType { DPDP, STORAGE, CONTAINMENT, DECOY, EXPLAINABILITY }

data class PolicyItem(
    val type: PolicyType,
    val clauseNumber: String,
    val statutoryRef: String,
    val title: String,
    val guarantee: String,
    val details: String,
)

data class PermissionRationale(
    val permissionName: String,
    val humanTitle: String,
    val scopeTag: String,
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
                clauseNumber = "SECTION 1",
                statutoryRef = "DPDP ACT 2023 · DATA MINIMISATION",
                title = "100% On-Device Processing Guarantee",
                guarantee = "Zero Cloud Telemetry. No telemetry, personal files, network packets, or device logs are ever transmitted off this device.",
                details = "All risk evaluations, application inspections, and behavioral scoring execute strictly in local memory without communicating with any external servers. Fully compliant with Sections 6 and 7 of India's Digital Personal Data Protection Act (DPDP Act 2023).",
            ),
            PolicyItem(
                type = PolicyType.STORAGE,
                clauseNumber = "SECTION 2",
                statutoryRef = "DATA RETENTION · STORAGE LIMITATION",
                title = "Automated 7-Day Storage Purge",
                guarantee = "Diagnostic and event logs are strictly rolling and permanently deleted after 7 days.",
                details = "Security event records and connection metadata are stored strictly in private, sandboxed local application storage. An automated background worker permanently removes records older than 7 days to eliminate persistent storage accumulation.",
            ),
            PolicyItem(
                type = PolicyType.CONTAINMENT,
                clauseNumber = "SECTION 3",
                statutoryRef = "TARGETED DEFENSE · SAFETY",
                title = "Surgical Per-Application Network Isolation",
                guarantee = "Isolation targets only the rogue application; general device internet connectivity remains uninterrupted.",
                details = "When critical unauthorized behavior is identified, containment isolates network access strictly for that offending application package. All other applications, phone calls, and internet services continue operating normally without requiring root privileges.",
            ),
            PolicyItem(
                type = PolicyType.DECOY,
                clauseNumber = "SECTION 4",
                statutoryRef = "USER CONTROL · ETHICAL TESTING",
                title = "Zero-Permission Decoy Honeypots",
                guarantee = "Decoy markers are completely sandboxed on-device and wiped upon toggle.",
                details = "Sandboxed local honeytokens (isolated private preferences UUID and credential markers) detect unauthorized background credential and device exfiltration attempts without requiring access to contacts or personal data.",
            ),
            PolicyItem(
                type = PolicyType.EXPLAINABILITY,
                clauseNumber = "SECTION 5",
                statutoryRef = "TRANSPARENCY · MULTI-LANGUAGE",
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
                scopeTag = "SYSTEM FOREGROUND SERVICE",
                rationale = "Maintains background security evaluation of newly installed apps and accessibility permission modifications without system termination.",
                mechanism = "Operates an Android Foreground Service with a transparent status notification.",
            ),
            PermissionRationale(
                permissionName = "android.net.VpnService (BIND_VPN_SERVICE)",
                humanTitle = "Local Traffic Isolation & Inspection",
                scopeTag = "LOCAL TUN INTERFACE ONLY",
                rationale = "Enables on-device packet inspection to detect command-and-control beaconing and isolates rogue apps locally without connecting to external VPN proxy servers.",
                mechanism = "Utilizes Android local TUN interface; loops traffic strictly inside the device.",
            ),
            PermissionRationale(
                permissionName = "android.permission.PACKAGE_USAGE_STATS",
                humanTitle = "Application Usage & Overlay Verification",
                scopeTag = "LOCAL USAGE STATS",
                rationale = "Detects when sensitive banking, payment, and government apps are brought to the foreground, preventing fraudulent background overlays from stealing credentials.",
                mechanism = "Queries standard Android UsageStats events for active window transitions.",
            ),
            PermissionRationale(
                permissionName = "android.permission.QUERY_ALL_PACKAGES",
                humanTitle = "Installed Application Inventory",
                scopeTag = "LOCAL PACKAGE MANAGER",
                rationale = "Audits installed packages to verify developer signing certificates against known threat registries, identify sideloaded apps, and detect hidden applications.",
                mechanism = "Inspects package metadata and signatures locally via Android PackageManager.",
            ),
            PermissionRationale(
                permissionName = "android.permission.POST_NOTIFICATIONS",
                humanTitle = "Critical Security Alerts",
                scopeTag = "LOCAL NOTIFICATIONS",
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
                    ScreenHeader(stringResource(R.string.set_privacy_policy), onBack)
                    Spacer(Modifier.height(14.dp))
                } else {
                    Spacer(Modifier.height(20.dp))
                }

                // Formal Institutional Document Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "LEGAL & COMPLIANCE SPECIFICATION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = MutedInk,
                    )
                    Text(
                        text = "Data Privacy Charter",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        text = "DHRASHTA-X operates on an air-gapped zero-cloud architecture. Security evaluation, heuristic analysis, and mitigation mechanisms execute strictly on-device without telemetry dispatch.",
                        fontSize = 13.sp,
                        color = MutedInk,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // Institutional Metadata Card
                QuietCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Line, RoundedCornerShape(14.dp)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MetadataRow(label = "Statutory Framework", value = "Digital Personal Data Protection Act (DPDP Act 2023)")
                        MetadataRow(label = "Telemetry Policy", value = "Zero Cloud Dispatch · Air-Gapped Local Inference")
                        MetadataRow(label = "Data Retention", value = "Strict 7-Day Rolling Purge on Private Storage")
                        MetadataRow(label = "Network Architecture", value = "Loopback TUN Interface (No External Proxy)")
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Segmented Tabs - Restrained Institutional Style
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Surface, RoundedCornerShape(10.dp))
                        .border(1.dp, Line, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TabSegment(
                        label = "Protection Principles",
                        count = policies.size,
                        selected = selectedTab == 0,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = 0 },
                    )
                    TabSegment(
                        label = "Services & Scopes",
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
                items(policies.size, key = { "policy-$it" }) { idx ->
                    val item = policies[idx]
                    PolicyCard(item)
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                // Section: Permissions & Services
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
                    QuietCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Line, RoundedCornerShape(14.dp)),
                    ) {
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
                                    checkedColor = Ink,
                                    uncheckedColor = MutedInk,
                                ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "I confirm that I have reviewed the DHRASHTA-X Privacy Charter, understand that the application operates strictly on-device with zero cloud telemetry, and grant consent for local security monitoring.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink,
                                lineHeight = 18.sp,
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
                    text = if (userAcknowledged) "Accept Terms & Continue" else "Acknowledge Policies to Proceed",
                    onClick = {
                        if (userAcknowledged) onAccept()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PrimaryAction(
                    text = "Return to Settings",
                    onClick = { onBack?.invoke() ?: onAccept() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MutedInk,
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
        )
    }
}

@Composable
private fun TabSegment(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SurfaceRaised else Color.Transparent)
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
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Ink else MutedInk,
            )
            Text(
                text = "($count)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MutedInk,
            )
        }
    }
}

@Composable
private fun PolicyCard(item: PolicyItem) {
    QuietCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(14.dp)),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.clauseNumber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MutedInk,
                    letterSpacing = 0.8.sp,
                )
                Text(
                    text = item.statutoryRef,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MutedInk,
                    letterSpacing = 0.4.sp,
                )
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )

            // Formal Inset Provision Box
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceRaised, RoundedCornerShape(8.dp))
                    .border(1.dp, Line, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = item.guarantee,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
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
    QuietCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(14.dp)),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.permissionName,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MutedInk,
                )

                Box(
                    modifier = Modifier
                        .background(SurfaceRaised, RoundedCornerShape(4.dp))
                        .border(1.dp, Line, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = item.scopeTag,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MutedInk,
                        letterSpacing = 0.5.sp,
                    )
                }
            }

            Text(
                text = item.humanTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceRaised, RoundedCornerShape(8.dp))
                    .border(1.dp, Line, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Technical Justification:",
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

            Text(
                text = "Mechanism: ${item.mechanism}",
                fontSize = 12.sp,
                color = MutedInk,
                lineHeight = 17.sp,
            )
        }
    }
}
