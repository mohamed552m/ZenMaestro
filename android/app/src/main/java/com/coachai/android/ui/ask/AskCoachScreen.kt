package com.coachai.android.ui.ask

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coachai.android.ui.theme.Accent
import com.coachai.android.ui.theme.AccentLine
import com.coachai.android.ui.theme.AccentSoft
import com.coachai.android.ui.theme.AccentText
import com.coachai.android.ui.theme.Background
import com.coachai.android.ui.theme.Border
import com.coachai.android.ui.theme.BorderSoft
import com.coachai.android.ui.theme.Card
import com.coachai.android.ui.theme.Raised
import com.coachai.android.ui.theme.Success
import com.coachai.android.ui.theme.TextMuted
import com.coachai.android.ui.theme.TextPrimary
import com.coachai.android.ui.theme.TextSubtle

data class AskMessage(
    val id: String,
    val text: String,
    val fromUser: Boolean,
)

private data class PromptSuggestion(
    val title: String,
    val prompt: String,
    val icon: ImageVector,
)

private val suggestions = listOf(
    PromptSuggestion(
        title = "Review today's plan",
        prompt = "Is my plan for today realistic?",
        icon = Icons.Outlined.CalendarMonth,
    ),
    PromptSuggestion(
        title = "Find my best time",
        prompt = "When am I usually most productive?",
        icon = Icons.Outlined.QueryStats,
    ),
    PromptSuggestion(
        title = "Learn from history",
        prompt = "What patterns can you see in my recent tasks?",
        icon = Icons.Outlined.History,
    ),
)

@Composable
fun AskCoachScreen(
    messages: List<AskMessage> = emptyList(),
    isSending: Boolean = false,
    isConnected: Boolean = false,
    onSendMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val canSend = isConnected && draft.isNotBlank() && !isSending
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible) {
            if (messages.isEmpty()) {
                listState.scrollToItem(0)
            } else {
                val lastItem = messages.size + if (isSending) 1 else 0
                listState.animateScrollToItem(lastItem)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        AskHeader(isConnected = isConnected)

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty()) {
                if (!isKeyboardVisible) {
                    item { WelcomeCard() }
                    item {
                        Text(
                            text = "TRY ASKING",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                        )
                    }
                }
                items(suggestions) { suggestion ->
                    SuggestionCard(suggestion) { draft = suggestion.prompt }
                }
            } else {
                item { ConversationIntro() }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                if (isSending) item { ThinkingBubble() }
            }
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            canSend = canSend,
            isConnected = isConnected,
            onSend = {
                val message = draft.trim()
                if (message.isNotEmpty()) {
                    onSendMessage(message)
                    draft = ""
                }
            },
        )
    }
}

@Composable
private fun AskHeader(isConnected: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF8B59), Color(0xFFFF6F3C)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Color(0xFF21130D), modifier = Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Ask Zen Maestro", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(if (isConnected) Success else TextSubtle),
                    )
                    Text(
                        text = if (isConnected) "Connected to your Zen Maestro data" else "Secure connection pending",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
            Surface(
                modifier = Modifier.size(38.dp),
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
}

@Composable
private fun WelcomeCard() {
    Surface(
        color = Card,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, AccentLine),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFFFFE7DC), Color(0xFFFFF4EF), Card),
                    ),
                ),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
                    .size(82.dp)
                    .clip(CircleShape)
                    .background(Accent.copy(alpha = .08f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Surface(
                        color = Accent.copy(alpha = .14f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Accent.copy(alpha = .32f)),
                    ) {
                        Text(
                            "CHAT WITH ZEN MAESTRO",
                            color = AccentText,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Text(
                        "Ask your AI coach.",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "I can review your plan, spot patterns and help choose your next step.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(58.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFFF9B70), Color(0xFFFF6F3C)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF2A150D),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: PromptSuggestion, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                color = Raised,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderSoft),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(suggestion.icon, null, tint = Accent, modifier = Modifier.size(19.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(suggestion.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(suggestion.prompt, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = Accent, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ConversationIntro() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
        Text(
            "ZEN MAESTRO CONVERSATION",
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MessageBubble(message: AskMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (message.fromUser) .84f else .92f),
            color = if (message.fromUser) AccentSoft else Card,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (message.fromUser) 20.dp else 6.dp,
                bottomEnd = if (message.fromUser) 6.dp else 20.dp,
            ),
            border = BorderStroke(1.dp, if (message.fromUser) AccentLine else Border),
        ) {
            Text(
                text = message.text,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Surface(
        color = Card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Text(
            "Checking your plan and progress…",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    canSend: Boolean,
    isConnected: Boolean,
    onSend: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Background)
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 9.dp),
    ) {
        Surface(
            color = Card,
            shape = RoundedCornerShape(19.dp),
            border = BorderStroke(1.dp, if (draft.isNotBlank()) AccentLine else Border),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 5.dp, end = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = draft,
                    onValueChange = { if (it.length <= 12_000) onDraftChange(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (isConnected) "Ask about your tasks or progress…" else "Zen Maestro connection is being prepared",
                            color = TextSubtle,
                        )
                    },
                    minLines = 1,
                    maxLines = 3,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
                Surface(
                    modifier = Modifier.size(44.dp),
                    color = if (canSend) Accent else Raised,
                    shape = RoundedCornerShape(15.dp),
                ) {
                    IconButton(onClick = onSend, enabled = canSend) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "Send message",
                            tint = if (canSend) Color(0xFF21130D) else TextSubtle,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }
        }
        if (isConnected) {
            Text(
                "Review important recommendations before acting.",
                color = TextSubtle,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 5.dp),
            )
        }
    }
}
