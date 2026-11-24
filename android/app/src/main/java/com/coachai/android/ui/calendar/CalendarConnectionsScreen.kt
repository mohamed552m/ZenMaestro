package com.coachai.android.ui.calendar

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coachai.android.ui.theme.Accent
import com.coachai.android.ui.theme.AccentSoft
import com.coachai.android.ui.theme.Background
import com.coachai.android.ui.theme.Border
import com.coachai.android.ui.theme.Card
import com.coachai.android.ui.theme.Danger
import com.coachai.android.ui.theme.Raised
import com.coachai.android.ui.theme.Success
import com.coachai.android.ui.theme.TextMuted
import com.coachai.android.ui.theme.TextPrimary
import com.coachai.android.data.CalendarFailureReason

@Composable
fun CalendarConnectionsScreen(
    uiState: CalendarConnectionsUiState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onToggleCalendar: (String) -> Unit,
    onSaveSelection: () -> Unit,
    onChangeCalendars: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onAuthorizationOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(uiState.authorizationUrl) {
        uiState.authorizationUrl?.let { url ->
            uriHandler.openUri(url)
            onAuthorizationOpened()
        }
    }
    Column(modifier.fillMaxSize().background(Background).statusBarsPadding()) {
        Header(onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                uiState.loading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
                uiState.failure != null -> CalendarError(uiState.failure, onRetry)
                uiState.choosingCalendars -> CalendarPicker(
                    calendars = uiState.availableCalendars,
                    message = uiState.message,
                    onToggle = onToggleCalendar,
                    onSave = onSaveSelection,
                )
                uiState.connection.connected && uiState.connection.selectedCalendars.isNotEmpty() -> ConnectedContent(
                    state = uiState,
                    onChangeCalendars = onChangeCalendars,
                    onSync = onSync,
                    onDisconnect = onDisconnect,
                )
                else -> DisconnectedContent(onConnect)
            }
            if (uiState.working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Working…", color = TextMuted, modifier = Modifier.padding(start = 9.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun CalendarError(reason: CalendarFailureReason, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Card, border = BorderStroke(1.dp, Border)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Calendar connection unavailable", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                when (reason) {
                    CalendarFailureReason.AUTHENTICATION_REQUIRED -> "Sign in is required to use your calendar."
                    CalendarFailureReason.NETWORK_ERROR -> "Check your connection and try again."
                    CalendarFailureReason.CONFIGURATION_ERROR -> "Google Calendar connection isn't available yet."
                    CalendarFailureReason.INVALID_RESPONSE -> "Calendar details couldn't be loaded safely."
                },
                color = TextMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = TextPrimary) }
        Column(Modifier.padding(start = 4.dp)) {
            Text("Calendar connections", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text("Plan around the time you already committed", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DisconnectedContent(onConnect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Card,
        border = BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(AccentSoft),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CalendarMonth, null, tint = Accent, modifier = Modifier.size(30.dp)) }
            Text(
                "A plan that respects your real day",
                color = TextPrimary,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                "Bring your calendar into Zen Maestro so meetings and appointments become protected time blocks.",
                color = TextMuted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 9.dp, bottom = 20.dp),
            )
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) {
                Text("Connect Google Calendar", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
    SectionLabel("WHAT ZEN MAESTRO WILL DO")
    Surface(shape = RoundedCornerShape(22.dp), color = Card, border = BorderStroke(1.dp, Border)) {
        Column(Modifier.fillMaxWidth()) {
            BenefitRow(Icons.Outlined.Schedule, "Protect busy time", "Tasks are planned around selected calendar events.")
            DividerLine()
            BenefitRow(Icons.Outlined.EventAvailable, "Keep plans realistic", "New plans use the time you actually have available.")
            DividerLine()
            BenefitRow(Icons.Outlined.Lock, "You stay in control", "Only the calendars you choose are included.")
        }
    }
}

@Composable
private fun CalendarPicker(
    calendars: List<ConnectedCalendar>,
    message: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
) {
    Text("Choose calendars", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text(
        "Selected calendars will be treated as fixed time. You can change this anytime.",
        color = TextMuted,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        calendars.forEach { calendar ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onToggle(calendar.calendarId) },
                shape = RoundedCornerShape(18.dp),
                color = if (calendar.selected) AccentSoft else Card,
                border = BorderStroke(1.dp, if (calendar.selected) Accent.copy(alpha = .55f) else Border),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(calendar.color)))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(calendar.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        if (calendar.isPrimary) Text("Primary calendar", color = TextMuted, fontSize = 12.sp)
                    }
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .background(if (calendar.selected) Accent else Raised),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (calendar.selected) Icon(Icons.Outlined.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    if (message != null) Text(message, color = Accent, fontSize = 13.sp)
    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
    ) { Text("Save & continue", fontWeight = FontWeight.Bold) }
}

@Composable
private fun ConnectedContent(
    state: CalendarConnectionsUiState,
    onChangeCalendars: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(24.dp), color = Card, border = BorderStroke(1.dp, Border)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Success.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Check, null, tint = Success)
                }
                Column(Modifier.padding(start = 13.dp)) {
                    Text("Google Calendar connected", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Last synced ${state.connection.lastSyncedAt ?: "not yet"}", color = TextMuted, fontSize = 13.sp)
                }
            }
            if (state.message != null) Text(state.message, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
    SectionLabel("USED FOR PLANNING")
    Surface(shape = RoundedCornerShape(22.dp), color = Card, border = BorderStroke(1.dp, Border)) {
        Column(Modifier.fillMaxWidth()) {
            state.connection.selectedCalendars.forEachIndexed { index, calendar ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(calendar.color)))
                    Text(calendar.name, Modifier.weight(1f).padding(start = 13.dp), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Included", color = Success, fontSize = 12.sp)
                }
                if (index != state.connection.selectedCalendars.lastIndex) DividerLine()
            }
        }
    }
    OutlinedButton(
        onClick = onChangeCalendars,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Border),
    ) { Text("Change calendars", color = TextPrimary, fontWeight = FontWeight.SemiBold) }
    Button(
        onClick = onSync,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentSoft),
    ) {
        Icon(Icons.Outlined.Refresh, null, tint = Accent, modifier = Modifier.size(19.dp))
        Text("Sync now", color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    }
    TextButton(onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Disconnect Google Calendar", color = Danger)
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            containerColor = Card,
            title = { Text("Disconnect calendar?", color = TextPrimary) },
            text = { Text("Calendar events will stop being used in future plans.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text("Disconnect", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel", color = TextPrimary) } },
        )
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Raised), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(23.dp))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun DividerLine() = Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(Border))

@Composable
private fun SectionLabel(text: String) = Text(
    text,
    color = TextMuted,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.2.sp,
    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
)
