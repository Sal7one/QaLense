package com.qalens.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.qalens.qaName
import com.qalens.qaTag

@Composable
fun SettingsScreen(darkTheme: Boolean, onToggleDark: () -> Unit, onProfileClick: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .qaTag("settings.title")
                    .qaName("Settings Title")
            )
        }

        // ── Profile tile ─────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onProfileClick)
                    .padding(16.dp)
                    .qaTag("settings.profile")
                    .qaName("Profile Row"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = UserAvatarUrl,
                    contentDescription = "Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Abdullah Al-Rashid",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.qaTag("settings.profile.name")
                    )
                    Text(
                        "QA Engineer · Gold Member",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }

        // ── Appearance ───────────────────────────────────────────────────
        item {
            SettingsSection(
                title = "Appearance",
                items = listOf(
                    SettingsItem.Toggle(
                        label    = if (darkTheme) "Dark Mode" else "Light Mode",
                        subtitle = if (darkTheme) "Switch to light theme" else "Switch to dark theme",
                        tag      = "settings.darkmode",
                        checked  = darkTheme,
                        onToggle = onToggleDark
                    )
                )
            )
            Spacer(Modifier.height(16.dp))
        }

        // ── App info ─────────────────────────────────────────────────────
        item {
            SettingsSection(
                title = "Application",
                items = listOf(
                    SettingsItem.Info("Version",     "1.0.0",     "settings.version"),
                    SettingsItem.Info("Build",       "debug-lab", "settings.build"),
                    SettingsItem.Info("Environment", "Dev",       "settings.env"),
                    SettingsItem.Info("QaLens",      "Active ✓",  "settings.qalens"),
                )
            )
        }
    }
}

private sealed class SettingsItem {
    data class Toggle(
        val label: String, val subtitle: String, val tag: String,
        val checked: Boolean, val onToggle: () -> Unit
    ) : SettingsItem()
    data class Info(val label: String, val value: String, val tag: String) : SettingsItem()
}

@Composable
private fun SettingsSection(title: String, items: List<SettingsItem>) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            items.forEachIndexed { idx, item ->
                when (item) {
                    is SettingsItem.Toggle -> SettingsToggleRow(item)
                    is SettingsItem.Info   -> SettingsInfoRow(item)
                }
                if (idx < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(item: SettingsItem.Toggle) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { item.onToggle() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .qaTag(item.tag)
            .qaName("${item.label} Toggle"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.label, fontWeight = FontWeight.Medium)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = item.checked, onCheckedChange = { item.onToggle() },
            modifier = Modifier.qaTag("${item.tag}.switch").qaName("${item.label} Switch")
        )
    }
}

@Composable
private fun SettingsInfoRow(item: SettingsItem.Info) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .qaTag(item.tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(item.label, color = MaterialTheme.colorScheme.onSurface)
        Text(
            item.value,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.qaTag("${item.tag}.value")
        )
    }
}

// ── Profile ───────────────────────────────────────────────────────────────────

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val totalBalance = SampleAccounts.sumOf { it.balance }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        item {
            Row(
                Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←",
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 14.dp)
                        .qaTag("profile.back")
                        .qaName("Profile Back")
                )
                Text(
                    "Profile",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.qaTag("profile.title")
                )
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AsyncImage(
                    model = UserAvatarUrl,
                    contentDescription = "Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .qaTag("profile.avatar")
                        .qaName("Profile Avatar")
                )
                Text(
                    "Abdullah Al-Rashid",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.qaTag("profile.name")
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        "Gold Member",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            val stats = listOf(
                Triple("Accounts",    "${SampleAccounts.size}",           "profile.stat.accounts"),
                Triple("Balance",     "%,.0f SAR".format(totalBalance),   "profile.stat.balance"),
                Triple("Member since","Jan 2022",                         "profile.stat.since"),
            )
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                stats.forEachIndexed { idx, (label, value, tag) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .qaTag(tag),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, fontWeight = FontWeight.SemiBold)
                    }
                    if (idx < stats.lastIndex) {
                        HorizontalDivider(
                            Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}
