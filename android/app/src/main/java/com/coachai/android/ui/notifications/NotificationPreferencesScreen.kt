package com.coachai.android.ui.notifications

import android.app.TimePickerDialog
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.coachai.android.data.remote.NotificationPreferences
import com.coachai.android.data.NotificationFailureReason
import com.coachai.android.ui.theme.Accent
import com.coachai.android.ui.theme.AccentSoft
import com.coachai.android.ui.theme.Background
import com.coachai.android.ui.theme.Border
import com.coachai.android.ui.theme.Card
import com.coachai.android.ui.theme.Raised
import com.coachai.android.ui.theme.TextMuted
import com.coachai.android.ui.theme.TextPrimary
import com.coachai.android.ui.settings.appHapticsEnabled
import com.coachai.android.ui.settings.formatAppTime
import com.coachai.android.ui.settings.reduceAppMotion
import com.coachai.android.ui.settings.uses24HourAppTime

@Composable
fun NotificationPreferencesScreen(
    uiState: NotificationPreferencesUiState,
    onBack: () -> Unit,
    onDailySummary: (Boolean) -> Unit,
    onCoachCheckins: (Boolean) -> Unit,
    onReminderLead: (Int?) -> Unit,
    onQuietHours: (String?, String?) -> Unit,
    onSound: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reduceMotion = context.reduceAppMotion()
    Column(
        modifier = modifier.fillMaxSize().background(Background).statusBarsPadding(),
    ) {
        Header(onBack)
        when (uiState) {
            NotificationPreferencesUiState.Loading -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
            ) {
                if (reduceMotion) {
                    Text("Loading notification settings…", color = TextMuted)
                } else {
                    CircularProgressIndicator(color = Accent)
                }
            }
            is NotificationPreferencesUiState.Error -> ErrorContent(uiState.reason, onRetry)
            is NotificationPreferencesUiState.Ready -> Content(
                preferences = uiState.preferences,
                saving = uiState.saving,
                saveError = uiState.saveError,
                onDailySummary = onDailySummary,
                onCoachCheckins = onCoachCheckins,
                onReminderLead = onReminderLead,
                onQuietHours = onQuietHours,
                onSound = onSound,
                onVibration = onVibration,
            )
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = TextPrimary)
        }
        Column(Modifier.padding(start = 4.dp)) {
            Text("Notifications", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text("Choose what deserves your attention", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Content(
    preferences: NotificationPreferences,
    saving: Boolean,
    saveError: NotificationFailureReason?,
    onDailySummary: (Boolean) -> Unit,
    onCoachCheckins: (Boolean) -> Unit,
    onReminderLead: (Int?) -> Unit,
    onQuietHours: (String?, String?) -> Unit,
    onSound: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
) {
    var showLeadDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    val use24Hour = context.uses24HourAppTime()
    fun haptic() {
        if (context.appHapticsEnabled()) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }
    val quietStart = preferences.quietHoursStart
    val quietEnd = preferences.quietHoursEnd
    val quietEnabled = quietStart != null && quietEnd != null

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (saving || saveError != null) {
            Text(
                text = if (saving) "Saving changes…" else failureMessage(saveError!!),
                color = if (saving) TextMuted else Accent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        SectionLabel("FOCUS & REST")
        SettingsGroup {
            SwitchRow(
                icon = Icons.Outlined.Bedtime,
                title = "Quiet hours",
                subtitle = if (quietEnabled) {
                    quietHoursLabel(quietStart, quietEnd, use24Hour)
                } else {
                    "Pause non-urgent notifications"
                },
                checked = quietEnabled,
                enabled = !saving,
                onChecked = { enabled ->
                    haptic()
                    onQuietHours(
                        if (enabled) quietStart ?: "22:00" else null,
                        if (enabled) quietEnd ?: "07:00" else null,
                    )
                },
            )
            if (quietEnabled) {
                DividerLine()
                TimeRangeRow(
                    start = quietStart,
                    end = quietEnd,
                    enabled = !saving,
                    onStartClick = {
                        showTimePicker(context, quietStart, use24Hour) { start ->
                            haptic()
                            onQuietHours(start, quietEnd)
                        }
                    },
                    onEndClick = {
                        showTimePicker(context, quietEnd, use24Hour) { end ->
                            haptic()
                            onQuietHours(quietStart, end)
                        }
                    },
                )
            }
        }

        SectionLabel("TASKS")
        SettingsGroup {
            ActionRow(
                icon = Icons.Outlined.Schedule,
                title = "Remind me",
                value = preferences.defaultReminderLeadMinutes?.let { "$it min before" } ?: "Off",
                enabled = !saving,
                onClick = {
                    haptic()
                    showLeadDialog = true
                },
            )
        }

        SectionLabel("COACH")
        SettingsGroup {
            SwitchRow(
                icon = Icons.Outlined.Summarize,
                title = "Daily summary",
                subtitle = "A short recap at the end of your day",
                checked = preferences.dailySummaryEnabled,
                enabled = !saving,
                onChecked = {
                    haptic()
                    onDailySummary(it)
                },
            )
            DividerLine()
            SwitchRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "Coach check-ins",
                subtitle = "Occasional guidance from Zen Maestro",
                checked = preferences.coachCheckinsEnabled,
                enabled = !saving,
                onChecked = {
                    haptic()
                    onCoachCheckins(it)
                },
            )
        }

        SectionLabel("DELIVERY")
        SettingsGroup {
            SwitchRow(
                icon = Icons.AutoMirrored.Outlined.VolumeUp,
                title = "Sound",
                subtitle = "Use the phone's notification sound",
                checked = preferences.soundEnabled,
                enabled = !saving,
                onChecked = {
                    haptic()
                    onSound(it)
                },
            )
            DividerLine()
            SwitchRow(
                icon = Icons.Outlined.Vibration,
                title = "Vibration",
                subtitle = "Use vibration with alerts",
                checked = preferences.vibrationEnabled,
                enabled = !saving,
                onChecked = {
                    haptic()
                    onVibration(it)
                },
            )
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showLeadDialog) {
        ReminderLeadDialog(
            selected = preferences.defaultReminderLeadMinutes,
            onDismiss = { showLeadDialog = false },
            onSelect = {
                showLeadDialog = false
                haptic()
                onReminderLead(it)
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextMuted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 9.dp, start = 4.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChecked(!checked) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onChecked else null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF2A160E),
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Raised,
                uncheckedBorderColor = Border,
            ),
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(value, color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted)
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Raised),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Accent, modifier = Modifier.size(19.dp)) }
}

@Composable
private fun DividerLine() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 15.dp).height(1.dp).background(Border))
}

private fun quietHoursLabel(start: String?, end: String?, use24Hour: Boolean): String {
    if (start == null || end == null) return "Off"
    return "${formatAppTime(start, use24Hour)} – ${formatAppTime(end, use24Hour)}"
}

@Composable
private fun TimeRangeRow(
    start: String,
    end: String,
    enabled: Boolean,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val use24Hour = LocalContext.current.uses24HourAppTime()
        TimeButton("FROM", start, use24Hour, Modifier.weight(1f), enabled, onStartClick)
        TimeButton("TO", end, use24Hour, Modifier.weight(1f), enabled, onEndClick)
    }
}

@Composable
private fun TimeButton(
    label: String,
    value: String,
    use24Hour: Boolean,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        color = Raised,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            Text(
                formatAppTime(value, use24Hour),
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ErrorContent(reason: NotificationFailureReason, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't load notification settings", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                failureMessage(reason),
                color = TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
            )
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

private fun failureMessage(reason: NotificationFailureReason): String = when (reason) {
    NotificationFailureReason.AUTHENTICATION_REQUIRED -> "Sign in is required to load your saved preferences."
    NotificationFailureReason.NETWORK_ERROR -> "Check your connection and try again."
    NotificationFailureReason.INVALID_RESPONSE -> "Notification settings couldn't be loaded safely."
}

private fun showTimePicker(
    context: Context,
    current: String?,
    use24Hour: Boolean,
    onPicked: (String) -> Unit,
) {
    val initial = runCatching { LocalTime.parse(current ?: "22:00") }.getOrDefault(LocalTime.of(22, 0))
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked("%02d:%02d".format(hour, minute)) },
        initial.hour,
        initial.minute,
        use24Hour,
    ).show()
}

@Composable
private fun ReminderLeadDialog(selected: Int?, onDismiss: () -> Unit, onSelect: (Int?) -> Unit) {
    val choices = listOf<Int?>(null, 5, 10, 15, 30)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Raised,
        title = { Text("Reminder time", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { minutes ->
                    val label = minutes?.let { "$it minutes before" } ?: "Off"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(minutes) }
                            .background(if (minutes == selected) AccentSoft else Color.Transparent)
                            .padding(12.dp),
                    ) { Text(label, color = if (minutes == selected) Accent else TextPrimary) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Accent) } },
    )
}
