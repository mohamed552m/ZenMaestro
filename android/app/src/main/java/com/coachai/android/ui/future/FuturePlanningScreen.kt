package com.coachai.android.ui.future

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coachai.android.data.remote.FutureCandidate
import com.coachai.android.data.remote.FutureOutcomePreview
import com.coachai.android.data.remote.FutureTaskInput
import com.coachai.android.ui.theme.Accent
import com.coachai.android.ui.theme.AccentLine
import com.coachai.android.ui.theme.AccentSoft
import com.coachai.android.ui.theme.AccentText
import com.coachai.android.ui.theme.Background
import com.coachai.android.ui.theme.Border
import com.coachai.android.ui.theme.Card
import com.coachai.android.ui.theme.Danger
import com.coachai.android.ui.theme.HeroEnd
import com.coachai.android.ui.theme.HeroStart
import com.coachai.android.ui.theme.Raised
import com.coachai.android.ui.theme.Success
import com.coachai.android.ui.theme.TextMuted
import com.coachai.android.ui.theme.TextPrimary
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun FuturePlanningScreen(
    viewModel: FuturePlanningViewModel,
    onBack: () -> Unit,
    onPlanChanged: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newTitle by remember { mutableStateOf("") }
    var newMinutes by remember { mutableStateOf("60") }
    var newPriority by remember { mutableIntStateOf(3) }
    var newDeadline by remember(state.end) { mutableStateOf(state.end) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().background(
            Brush.verticalGradient(listOf(HeroStart, Background, Background)),
        ),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(58.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = TextPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Future Planning", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("Turn deadlines into a realistic plan", color = TextMuted, fontSize = 12.sp)
                }
                Box(
                    Modifier.size(38.dp).background(AccentSoft, CircleShape)
                        .border(1.dp, AccentLine, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.AutoAwesome, null, tint = AccentText) }
            }
        }

        item { PlanningHero() }
        item {
            SectionCard(number = "1", title = "Choose the planning window") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScopeButton("Date range", !state.specificDay) { viewModel.setSpecificDay(false) }
                    ScopeButton("One day", state.specificDay) { viewModel.setSpecificDay(true) }
                }
                Spacer(Modifier.height(12.dp))
                DateField("Starts", state.start, LocalDate.now(), LocalDate.now().plusWeeks(12), viewModel::setStart)
                if (!state.specificDay) {
                    Spacer(Modifier.height(8.dp))
                    DateField("Deadline window ends", state.end, state.start, state.start.plusWeeks(12), viewModel::setEnd)
                }
                Spacer(Modifier.height(8.dp))
                WorkStartField(state.workStart, viewModel::setWorkStart)
                Text(
                    "Nothing before today can be changed. Zen Maestro will use free time between these dates.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        item {
            SectionCard(number = "2", title = "Choose work and deadlines") {
                Text(
                    "Select saved tasks, or add new work below. Only the selected tasks will be redistributed.",
                    color = TextMuted,
                    fontSize = 13.sp,
                )
            }
        }

        if (state.loading && state.candidates.isEmpty()) {
            item { LoadingCard("Loading eligible tasks…") }
        } else if (state.candidates.isEmpty()) {
            item {
                QuietCard {
                    Text("No flexible saved tasks in this period", fontWeight = FontWeight.Bold)
                    Text("Add the tasks you want to finish and give each one a deadline.", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            items(state.candidates, key = { it.taskId }) { candidate ->
                CandidateCard(
                    candidate = candidate,
                    selected = state.selected[candidate.taskId],
                    planningStart = state.start,
                    planningEnd = state.end,
                    onToggle = { viewModel.toggle(candidate) },
                    onUpdate = { minutes, priority, deadline ->
                        viewModel.updateSelected(candidate.taskId, minutes, priority, deadline)
                    },
                )
            }
        }

        items(state.drafts, key = { it.temporaryId.orEmpty() }) { draft ->
            DraftSummary(draft, onRemove = { viewModel.removeDraft(draft.temporaryId.orEmpty()) })
        }

        item {
            QuietCard {
                Text("Add a task", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("What needs to be done?") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newMinutes,
                        onValueChange = { newMinutes = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    PriorityPicker(newPriority, Modifier.weight(1.35f)) { newPriority = it }
                }
                Spacer(Modifier.height(8.dp))
                DateField("Finish by", newDeadline, state.start, state.end) { newDeadline = it }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.addDraft(newTitle, newMinutes.toIntOrNull() ?: 0, newPriority, newDeadline)
                        if (newTitle.isNotBlank() && (newMinutes.toIntOrNull() ?: 0) > 0) {
                            newTitle = ""; newMinutes = "60"; newPriority = 3
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, AccentLine),
                ) { Text("＋ Add to this plan", color = AccentText, fontWeight = FontWeight.Bold) }
            }
        }

        state.error?.let { message ->
            item { FeedbackCard(message, Danger) { viewModel.clearFeedback() } }
        }
        state.success?.let { message ->
            item { FeedbackCard(message, Success) { viewModel.clearFeedback() } }
        }

        item {
            SectionCard(number = "3", title = "Preview before saving") {
                Text(
                    "Zen Maestro respects deadlines, balances the daily load, and may split long work into focused sessions.",
                    color = TextMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::preview,
                    enabled = !state.loading && !state.applying,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("Build my preview", fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        state.preview?.let { preview ->
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Placed", preview.placedCount.toString(), Success, Modifier.weight(1f))
                    MetricCard("Need changes", preview.unplacedCount.toString(), if (preview.unplacedCount == 0) Success else Danger, Modifier.weight(1f))
                }
            }
            items(preview.outcomes, key = { "${it.title}-${it.reason}" }) { outcome ->
                OutcomeCard(outcome)
            }
            item {
                Button(
                    onClick = { viewModel.apply(onPlanChanged) },
                    enabled = !state.applying && preview.unplacedCount == 0 && state.operationId == null,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    if (state.applying) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    else {
                        Icon(Icons.Outlined.CheckCircle, null)
                        Text(
                            if (state.operationId == null) "  Save this future plan" else "  Plan saved",
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                if (state.operationId != null) {
                    TextButton(
                        onClick = { viewModel.undo(onPlanChanged) },
                        enabled = !state.applying,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Undo the saved plan", color = Danger) }
                }
            }
        }
    }
}

@Composable
private fun PlanningHero() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Card),
        border = BorderStroke(1.dp, Border),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).background(AccentSoft, RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CalendarMonth, null, tint = Accent, modifier = Modifier.size(29.dp)) }
            Column(Modifier.padding(start = 14.dp)) {
                Text("Give every deadline room", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("See the whole week before it becomes stressful.", color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SectionCard(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    QuietCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                Text(number, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
            Text(title, Modifier.padding(start = 10.dp), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun QuietCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Card),
        border = BorderStroke(1.dp, Border),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
private fun RowScope.ScopeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (selected) Accent else Border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) AccentSoft else Color.Transparent),
    ) { Text(label, color = if (selected) AccentText else TextMuted, fontWeight = FontWeight.Bold) }
}

@Composable
private fun DateField(label: String, value: LocalDate, min: LocalDate, max: LocalDate, onDate: (LocalDate) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable {
            DatePickerDialog(
                context,
                { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) },
                value.year, value.monthValue - 1, value.dayOfMonth,
            ).apply {
                datePicker.minDate = min.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                datePicker.maxDate = max.atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.show()
        }.background(Raised, RoundedCornerShape(14.dp)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CalendarMonth, null, tint = AccentText)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(label, color = TextMuted, fontSize = 11.sp)
            Text(value.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")), fontWeight = FontWeight.Bold)
        }
        Text("Change", color = AccentText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WorkStartField(value: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable {
            val pieces = value.split(':')
            TimePickerDialog(context, { _, hour, minute -> onValue("%02d:%02d".format(hour, minute)) }, pieces[0].toInt(), pieces[1].toInt(), true).show()
        }.background(Raised, RoundedCornerShape(14.dp)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, null, tint = AccentText)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("Start planning from", color = TextMuted, fontSize = 11.sp)
            Text(value, fontWeight = FontWeight.Bold)
        }
        Text("Change", color = AccentText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CandidateCard(
    candidate: FutureCandidate,
    selected: FutureTaskInput?,
    planningStart: LocalDate,
    planningEnd: LocalDate,
    onToggle: () -> Unit,
    onUpdate: (Int?, Int?, LocalDate?) -> Unit,
) {
    var minutes by remember(selected?.remainingMinutes) { mutableStateOf((selected?.remainingMinutes ?: candidate.remainingMinutes).toString()) }
    val deadline = selected?.deadline?.let(::parseDateTimeDate)?.coerceIn(planningStart, planningEnd) ?: planningEnd
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected != null) AccentSoft else Card),
        border = BorderStroke(1.dp, if (selected != null) AccentLine else Border),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected != null, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f).clickable(onClick = onToggle)) {
                    Text(candidate.title, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Saved for ${candidate.planDate.format(DateTimeFormatter.ofPattern("MMM d"))}", color = TextMuted, fontSize = 12.sp)
                }
            }
            if (selected != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { value -> minutes = value.filter(Char::isDigit).take(5); minutes.toIntOrNull()?.let { onUpdate(it, null, null) } },
                        modifier = Modifier.weight(0.8f),
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    PriorityPicker(selected.priority, Modifier.weight(1.2f)) { onUpdate(null, it, null) }
                }
                Spacer(Modifier.height(8.dp))
                DateField("Finish by", deadline, planningStart, planningEnd) { onUpdate(null, null, it) }
            }
        }
    }
}

@Composable
private fun PriorityPicker(value: Int, modifier: Modifier = Modifier, onValue: (Int) -> Unit) {
    Column(modifier) {
        Text("Priority", color = TextMuted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1 to "High", 3 to "Med", 5 to "Low").forEach { (priority, label) ->
                val selected = value == priority || (value == 2 && priority == 1) || (value == 4 && priority == 5)
                Box(
                    Modifier.weight(1f).clickable { onValue(priority) }
                        .background(if (selected) AccentSoft else Raised, RoundedCornerShape(9.dp))
                        .border(1.dp, if (selected) AccentLine else Border, RoundedCornerShape(9.dp))
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (selected) AccentText else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun DraftSummary(draft: FutureTaskInput, onRemove: () -> Unit) {
    QuietCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(Accent, CircleShape))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(draft.title, fontWeight = FontWeight.ExtraBold)
                Text("${draft.remainingMinutes} min · deadline ${draft.deadline?.let(::parseDateTimeDate) ?: "—"}", color = TextMuted, fontSize = 12.sp)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.DeleteOutline, "Remove", tint = Danger) }
        }
    }
}

@Composable
private fun OutcomeCard(outcome: FutureOutcomePreview) {
    QuietCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (outcome.placed) Icons.Outlined.CheckCircle else Icons.Outlined.CalendarMonth,
                null,
                tint = if (outcome.placed) Success else Danger,
            )
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(outcome.title, fontWeight = FontWeight.ExtraBold)
                Text("${outcome.remainingMinutes} minutes", color = TextMuted, fontSize = 12.sp)
            }
        }
        if (outcome.segments.isEmpty()) {
            Text(outcome.message, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        } else {
            outcome.segments.forEach { segment ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).background(Raised, RoundedCornerShape(12.dp)).padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(segment.date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("${segment.start} – ${segment.end}", color = AccentText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Card), border = BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(15.dp)) {
            Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LoadingCard(text: String) {
    QuietCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
            Text(text, Modifier.padding(start = 12.dp), color = TextMuted)
        }
    }
}

@Composable
private fun FeedbackCard(text: String, color: Color, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)), border = BorderStroke(1.dp, color.copy(alpha = 0.45f)), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = TextPrimary, fontSize = 13.sp)
            TextButton(onClick = onDismiss) { Text("Close", color = color) }
        }
    }
}

private fun parseDateTimeDate(value: String): LocalDate = runCatching {
    LocalDateTime.parse(value.replace(' ', 'T')).toLocalDate()
}.getOrElse { LocalDate.parse(value.take(10)) }

