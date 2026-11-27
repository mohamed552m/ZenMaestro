package com.coachai.android.ui.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coachai.android.domain.HomeTask
import com.coachai.android.domain.TaskStatus
import com.coachai.android.ui.theme.Accent
import com.coachai.android.ui.theme.AccentLine
import com.coachai.android.ui.theme.AccentSoft
import com.coachai.android.ui.theme.AccentText
import com.coachai.android.ui.theme.Amber
import com.coachai.android.ui.theme.Background
import com.coachai.android.ui.theme.Border
import com.coachai.android.ui.theme.BorderSoft
import com.coachai.android.ui.theme.Card
import com.coachai.android.ui.theme.NavigationSurface
import com.coachai.android.ui.theme.Raised
import com.coachai.android.ui.theme.Success
import com.coachai.android.ui.theme.TextMuted
import com.coachai.android.ui.theme.TextPrimary
import com.coachai.android.ui.theme.TextSubtle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class PlanSelection { Today, Past }

private data class ReportInsight(
    val summary: String,
    val detail: String,
    val strengths: List<Pair<String, String>>,
    val improvements: List<Pair<String, String>>,
    val recommendations: List<String>,
)

@Composable
fun CoachReportScreen(
    tasks: List<HomeTask>,
    uiState: CoachReportUiState,
    onGenerate: () -> Unit,
    onReset: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateSchedule: () -> Unit,
) {
    var selectedPlan by rememberSaveable { mutableStateOf(PlanSelection.Today) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            BrandHeader()
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { PageHeader() }
                item {
                    ScheduleContext(
                        tasks = tasks,
                        selected = selectedPlan,
                        onSelect = {
                            selectedPlan = it
                            onReset()
                        },
                    )
                }

                when {
                    selectedPlan == PlanSelection.Past -> item { EmptyPastPlan() }
                    tasks.isEmpty() -> item { EmptyPlan(onNavigateSchedule) }
                    uiState == CoachReportUiState.Idle -> item {
                        ReadyCard(tasks, onGenerate)
                    }
                    uiState == CoachReportUiState.Loading -> item { LoadingCard() }
                    uiState is CoachReportUiState.Error -> item {
                        CoachErrorCard(uiState.reason, onGenerate)
                    }
                    uiState is CoachReportUiState.Ready -> item {
                        ReportContent(uiState.report.toInsight(), tasks)
                    }
                }
            }
        }
    }
}

private fun com.coachai.android.data.remote.CoachRecommendation.toInsight() = ReportInsight(
    summary = summary,
    detail = summary,
    strengths = strengths.map { "Strength" to it },
    improvements = weaknesses.map { "Improve" to it },
    recommendations = recommendations,
)

@Composable
private fun CoachErrorCard(reason: com.coachai.android.data.CoachFailureReason, onRetry: () -> Unit) {
    AppCard {
        Text("Couldn't generate the report", color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(
            when (reason) {
                com.coachai.android.data.CoachFailureReason.AUTHENTICATION_REQUIRED -> "Your session expired. Log in again to generate the report."
                com.coachai.android.data.CoachFailureReason.NETWORK_ERROR -> "Can't reach the server. Check your connection and try again."
                com.coachai.android.data.CoachFailureReason.CONFIGURATION_ERROR -> "The AI coach isn't configured on the server yet."
                com.coachai.android.data.CoachFailureReason.INVALID_RESPONSE -> "That report couldn't be displayed safely."
                com.coachai.android.data.CoachFailureReason.AI_UNAVAILABLE -> "The AI coach couldn't answer right now. Try again in a moment."
                com.coachai.android.data.CoachFailureReason.NO_SCHEDULE -> "Add a few tasks to today's plan first, then generate the report."
                com.coachai.android.data.CoachFailureReason.TIMEOUT -> "The AI coach took too long to answer. Try again."
            },
            color = TextMuted,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        PrimaryButton("Try again", onRetry)
    }
}

@Composable
private fun BrandHeader() {
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFFF8B59), Color(0xFFFF6F3C)))),
            contentAlignment = Alignment.Center,
        ) {
            Text("C", color = Color(0xFF21130D), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text("Zen Maestro", style = MaterialTheme.typography.titleLarge, fontSize = 20.sp)
            Spacer(Modifier.width(3.dp))
            Box(Modifier.padding(bottom = 4.dp).size(6.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
        }
        Surface(
            modifier = Modifier.size(36.dp),
            color = AccentSoft,
            shape = CircleShape,
            border = BorderStroke(1.dp, AccentLine),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("AH", color = AccentText, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PageHeader() {
    Column(Modifier.padding(top = 12.dp, bottom = 6.dp)) {
        Text("PERSONAL GUIDANCE", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(5.dp))
        Text("Coach Report", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Generate a coaching report from your current schedule.",
            color = TextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ScheduleContext(
    tasks: List<HomeTask>,
    selected: PlanSelection,
    onSelect: (PlanSelection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(com.coachai.android.ui.theme.Surface)
                .padding(4.dp),
        ) {
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (selected == PlanSelection.Today) AccentSoft else Color.Transparent)
                    .clickable { onSelect(PlanSelection.Today) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Today's plan",
                    color = if (selected == PlanSelection.Today) AccentText else TextMuted,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (selected == PlanSelection.Past) AccentSoft else Color.Transparent)
                    .clickable { onSelect(PlanSelection.Past) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "A past plan",
                    color = if (selected == PlanSelection.Past) AccentText else TextMuted,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (selected == PlanSelection.Past || tasks.isEmpty()) Amber else Success),
            )
            Spacer(Modifier.width(8.dp))
            Text("Schedule and history", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(
                if (selected == PlanSelection.Today) "Today · ${tasks.size} tasks" else "Past plans · History",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReadyCard(tasks: List<HomeTask>, onGenerate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Outlined.CalendarMonth)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), fontWeight = FontWeight.Bold)
                    Text("Today's plan is ready to analyse", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Surface(color = Raised, shape = CircleShape, border = BorderStroke(1.dp, Border)) {
                    Text("${tasks.size} tasks", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            PrimaryButton("Generate coaching report", onGenerate)
        }
        AppCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 25.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(Icons.Outlined.Assessment, 56)
                Spacer(Modifier.height(14.dp))
                Text("No report generated yet", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Your report will use the tasks currently shown in Schedule.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(29.dp), color = Accent, strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Preparing your report", fontWeight = FontWeight.Bold)
                Text("Analysing the current schedule…", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EmptyPlan(onNavigateSchedule: () -> Unit) {
    AppCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(Icons.Outlined.CalendarMonth, 58)
            Spacer(Modifier.height(14.dp))
            Text("No plan for today yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Add at least one task in Schedule, then return here to generate your coaching report.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            PrimaryButton("Open Schedule", onNavigateSchedule)
        }
    }
}

@Composable
private fun EmptyPastPlan() {
    AppCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(Icons.Outlined.Assessment, 58)
            Spacer(Modifier.height(14.dp))
            Text("No past plans saved yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Past plans will appear here after completed schedules are added to history.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReportContent(report: ReportInsight, tasks: List<HomeTask>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Your coaching report", "${tasks.size} tasks")
        Surface(
            color = Card,
            shape = RoundedCornerShape(19.dp),
            border = BorderStroke(1.dp, AccentLine),
        ) {
            Column(
                Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(AccentSoft, Card)))
                    .padding(20.dp),
            ) {
                Text("COACH'S SUMMARY", color = AccentText, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(11.dp))
                Text(report.summary, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(report.detail, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SectionTitle("Strengths", "${report.strengths.size} identified")
        InsightList(report.strengths, Success)
        SectionTitle("Areas to improve", "${report.improvements.size} identified")
        InsightList(report.improvements, Amber)
        SectionTitle("Recommendations", "${report.recommendations.size} actions")
        AppCard(contentPadding = PaddingValues(0.dp)) {
            report.recommendations.forEachIndexed { index, recommendation ->
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
                    NumberBadge(index + 1, Accent)
                    Spacer(Modifier.width(12.dp))
                    Text(recommendation, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                }
                if (index < report.recommendations.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(BorderSoft))
                }
            }
        }
        Text(
            "This report reflects the current Schedule plan. It does not modify your tasks.",
            color = TextSubtle,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SectionTitle(title: String, meta: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(meta, color = TextMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InsightList(items: List<Pair<String, String>>, color: Color) {
    AppCard(contentPadding = PaddingValues(0.dp)) {
        items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
                NumberBadge(index + 1, color)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(item.first, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(item.second, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (index < items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(BorderSoft))
        }
    }
}

@Composable
private fun NumberBadge(number: Int, color: Color) {
    Surface(
        modifier = Modifier.size(31.dp),
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(number.toString().padStart(2, '0'), color = color, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, size: Int = 42) {
    Surface(
        modifier = Modifier.size(size.dp),
        color = AccentSoft,
        shape = RoundedCornerShape(if (size > 50) 18.dp else 13.dp),
        border = BorderStroke(1.dp, AccentLine),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = AccentText, modifier = Modifier.size((size / 2).dp))
        }
    }
}

@Composable
private fun AppCard(
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Card,
        shape = RoundedCornerShape(19.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.padding(contentPadding), content = { content() })
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF20130E)),
    ) {
        Text(label, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun CoachBottomNavigation(onHome: () -> Unit, onSchedule: () -> Unit) {
    data class Entry(val label: String, val icon: ImageVector, val selected: Boolean, val action: () -> Unit)
    val entries = listOf(
        Entry("Home", Icons.Outlined.Home, false, onHome),
        Entry("Schedule", Icons.Outlined.CalendarMonth, false, onSchedule),
        Entry("Coach", Icons.Outlined.Assessment, true, {}),
        Entry("Ask", Icons.Outlined.ChatBubbleOutline, false, {}),
        Entry("More", Icons.Outlined.MoreHoriz, false, {}),
    )
    NavigationBar(
        modifier = Modifier.height(88.dp).navigationBarsPadding(),
        containerColor = NavigationSurface,
    ) {
        entries.forEach { entry ->
            NavigationBarItem(
                selected = entry.selected,
                onClick = entry.action,
                icon = { Icon(entry.icon, contentDescription = entry.label, modifier = Modifier.size(25.dp)) },
                label = { Text(entry.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = AccentText,
                    indicatorColor = AccentSoft,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                ),
            )
        }
    }
}

private fun buildReport(tasks: List<HomeTask>): ReportInsight {
    val completed = tasks.count { it.status == TaskStatus.COMPLETED }
    val totalMinutes = tasks.sumOf { it.estimatedMinutes }
    val highPriority = tasks.count { it.priority >= 3 }
    val fixed = tasks.count { it.isFixedTime }
    val firstTask = tasks.minByOrNull { it.scheduledStart ?: "99:99" }
    val heavy = totalMinutes >= 360 || highPriority >= 3

    val strengths = buildList {
        firstTask?.let {
            add("The day has a clear starting point" to "${it.title} is the first scheduled focus item.")
        }
        if (fixed > 0) add("Fixed commitments are protected" to "$fixed fixed-time item${if (fixed == 1) " is" else "s are"} visible in the plan.")
        if (completed > 0) add("Progress is already visible" to "$completed of ${tasks.size} tasks have been completed.")
        if (isEmpty()) add("The plan is ready to follow" to "${tasks.size} tasks are available in a single schedule.")
    }.take(2)

    val improvements = buildList {
        if (heavy) add("The workload is concentrated" to "$totalMinutes planned minutes and $highPriority high-priority items may reduce flexibility.")
        if (tasks.none { it.scheduledStart != null }) add("Tasks need scheduled times" to "The plan has tasks, but no start times are currently assigned.")
        if (completed == 0) add("Execution has not started yet" to "No task in today's plan is marked complete.")
        if (isEmpty()) add("Protect recovery time" to "Keep space between demanding tasks before adding more work.")
    }.take(2)

    val recommendations = buildList {
        firstTask?.let { add("Start with ${it.title} and protect that block from smaller interruptions.") }
        if (heavy) add("Avoid adding another demanding task until the current workload is reduced.")
        if (tasks.any { it.scheduledStart == null }) add("Run Smart Scheduler so every flexible task receives a realistic start time.")
        add("Review the plan after the first completed task and adjust only if the remaining workload changes.")
    }.distinct().take(3)

    return ReportInsight(
        summary = if (heavy) {
            "Your plan is actionable, but the workload leaves limited room for delays."
        } else {
            "Your plan is balanced enough to begin, with a clear set of priorities."
        },
        detail = "Today's schedule contains ${tasks.size} tasks, $totalMinutes planned minutes, and $completed completed item${if (completed == 1) "" else "s"}.",
        strengths = strengths,
        improvements = improvements,
        recommendations = recommendations,
    )
}
