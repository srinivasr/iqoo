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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

data class PolicySection(
    val sectionNumber: String,
    val statutoryRef: String,
    val title: String,
    val guarantee: String,
    val details: String,
)

data class PermissionDetail(
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

    var userAcknowledged by remember { mutableStateOf(!onboarding) }

    val sections = remember {
        listOf(
            PolicySection(
                sectionNumber = "SECTION 1",
                statutoryRef = "DPDP ACT 2023 §6",
                title = "100% On-Device Processing Guarantee",
                guarantee = "Zero Cloud Telemetry. No telemetry, personal files, network packets, or device logs are ever transmitted off this device.",
                details = "All risk evaluations, application inspections, and behavioral scoring execute strictly in local memory without communicating with any external servers. Fully compliant with Sections 6 and 7 of India's Digital Personal Data Protection Act (DPDP Act 2023).",
            ),
            PolicySection(
                sectionNumber = "SECTION 2",
                statutoryRef = "DATA RETENTION",
                title = "Automated 7-Day Storage Purge",
                guarantee = "Diagnostic and event logs are strictly rolling and permanently deleted after 7 days.",
                details = "Security event records and connection metadata are stored strictly in private, sandboxed local application storage. An automated background worker permanently removes records older than 7 days to eliminate persistent storage accumulation.",
            ),
            PolicySection(
                sectionNumber = "SECTION 3",
                statutoryRef = "USER GOVERNANCE",
                title = "Surgical Per-Application Network Isolation",
                guarantee = "Isolation targets only the rogue application; general device internet connectivity remains uninterrupted.",
                details = "When critical unauthorized behavior is identified, containment isolates network access strictly for that offending application package. All other applications, phone calls, and internet services continue operating normally without requiring root privileges.",
            ),
            PolicySection(
                sectionNumber = "SECTION 4",
                statutoryRef = "LOCAL HONEYTOKENS",
                title = "Zero-Permission Decoy Honeypots",
                guarantee = "Decoy markers are completely sandboxed on-device and wiped upon toggle.",
                details = "Sandboxed local honeytokens (isolated private preferences UUID and credential markers) detect unauthorized background credential and device exfiltration attempts without requiring access to contacts or personal data.",
            ),
            PolicySection(
                sectionNumber = "SECTION 5",
                statutoryRef = "TRANSPARENCY",
                title = "Transparent Behavioral Evidence",
                guarantee = "Every risk evaluation is backed by visible factors with 13 Indian language translations.",
                details = "All threat assessments present concrete, auditable factors—such as hidden launcher icons, unauthorized background overlays, or unsigned certificates—available in English and 12 regional Indian languages with optional voice assistance.",
            ),
        )
    }

    val permissions = remember {
        listOf(
            PermissionDetail(
                permissionName = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                humanTitle = "Continuous System Protection Service",
                scopeTag = "SYSTEM FOREGROUND SERVICE",
                rationale = "Maintains background security evaluation of newly installed apps and accessibility permission modifications without system termination.",
                mechanism = "Operates an Android Foreground Service with a transparent status notification.",
            ),
            PermissionDetail(
                permissionName = "android.net.VpnService",
                humanTitle = "Local Traffic Isolation & Inspection",
                scopeTag = "LOCAL TUN ONLY",
                rationale = "Enables on-device packet inspection to detect command-and-control beaconing and isolates rogue apps locally without connecting to external VPN proxy servers.",
                mechanism = "Utilizes Android local TUN interface; loops traffic strictly inside the device.",
            ),
            PermissionDetail(
                permissionName = "android.permission.PACKAGE_USAGE_STATS",
                humanTitle = "Application Usage & Overlay Verification",
                scopeTag = "LOCAL USAGE STATS",
                rationale = "Detects when sensitive banking, payment, and government apps are brought to the foreground, preventing fraudulent background overlays from stealing credentials.",
                mechanism = "Queries standard Android UsageStats events for active window transitions.",
            ),
            PermissionDetail(
                permissionName = "android.permission.QUERY_ALL_PACKAGES",
                humanTitle = "Installed Application Inventory",
                scopeTag = "LOCAL PACKAGE MANAGER",
                rationale = "Audits installed packages to verify developer signing certificates against known threat registries, identify sideloaded apps, and detect hidden applications.",
                mechanism = "Inspects package metadata and signatures locally via Android PackageManager.",
            ),
            PermissionDetail(
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
                if (onBack != null) {
                    ScreenHeader(stringResource(R.string.set_privacy_policy), onBack)
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.set_privacy_policy),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }

                Text(
                    text = "Last updated: October 2026 · Statutory Reference: DPDP Act 2023",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MutedInk,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                Text(
                    text = "DHRASHTA-X operates on a strict zero-cloud, air-gapped architecture. All behavioral heuristics, machine learning inference, and threat containment execute entirely on your physical device. No telemetry, user logs, or network payloads are ever transmitted to external servers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                HorizontalDivider(color = Line, modifier = Modifier.padding(bottom = 16.dp))

                Text(
                    text = "Data Protection Principles",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // Continuous Section: Principles
            items(sections.size, key = { "sec-$it" }) { idx ->
                val item = sections[idx]
                PolicySectionCard(item)
                Spacer(Modifier.height(12.dp))
            }

            // Continuous Section: System Permissions
            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Line, modifier = Modifier.padding(bottom = 16.dp))

                Text(
                    text = "Android Permissions & Defensive Scopes",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Text(
                    text = "Every privilege requested is strictly bounded to an on-device defensive mechanism. No data collected through these privileges leaves local memory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }

            items(permissions.size, key = { "perm-$it" }) { idx ->
                val item = permissions[idx]
                PermissionDetailCard(item)
                Spacer(Modifier.height(12.dp))
            }

            // Final Legal Provisions & Contact
            item {
                Spacer(Modifier.height(8.dp))
                QuietCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Line, RoundedCornerShape(14.dp)),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Children's Privacy & Contact",
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink,
                        )
                        Text(
                            text = "DHRASHTA-X does not collect personal information from any user, including children under 13. All data remains in private sandboxed SQLite storage and is permanently deleted upon uninstallation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedInk,
                            lineHeight = 19.sp,
                        )
                        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "Inquiries & Audit Verification: privacy@dhrashta.com",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                        )
                    }
                }
                Spacer(Modifier.height(if (onboarding) 16.dp else 36.dp))
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
                                text = "I confirm that I have reviewed the DHRASHTA-X Privacy Policy, understand that all operations execute strictly on-device with zero cloud telemetry, and grant consent for local security monitoring.",
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

        // Bottom Action Bar ONLY during onboarding
        if (onboarding) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .border(1.dp, Line)
                    .padding(horizontal = ScreenPadding, vertical = 14.dp)
                    .navigationBarsPadding(),
            ) {
                PrimaryAction(
                    text = if (userAcknowledged) "Accept Terms & Continue" else "Acknowledge to Proceed",
                    onClick = {
                        if (userAcknowledged) onAccept()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PolicySectionCard(item: PolicySection) {
    QuietCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(14.dp)),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.sectionNumber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MutedInk,
                    letterSpacing = 0.8.sp,
                )
                Text(
                    text = item.statutoryRef,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MutedInk,
                )
            }

            Text(
                text = item.title,
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
private fun PermissionDetailCard(item: PermissionDetail) {
    QuietCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(14.dp)),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.scopeTag,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MutedInk,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    text = item.permissionName,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                )
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
                text = "Enforcement Mechanism: ${item.mechanism}",
                fontSize = 12.sp,
                color = MutedInk,
                lineHeight = 17.sp,
            )
        }
    }
}
