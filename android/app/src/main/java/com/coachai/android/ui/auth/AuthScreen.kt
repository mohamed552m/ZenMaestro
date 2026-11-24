package com.coachai.android.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.coachai.android.ui.theme.Accent
import com.coachai.android.ui.theme.Background
import com.coachai.android.ui.theme.Border
import com.coachai.android.ui.theme.Card
import com.coachai.android.ui.theme.Danger
import com.coachai.android.ui.theme.Success
import com.coachai.android.ui.theme.TextMuted
import com.coachai.android.ui.theme.TextPrimary
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import kotlinx.coroutines.launch

private enum class AuthMode { Login, Register, Reset }

private val AuthOrange = Color(0xFFC45127)
private val AuthNavy = Color(0xFF23263D)
private val AuthNavyGlow = Color(0xFF383A51)
private val AuthSoft = Color(0xFFFFF0E8)
private val AuthTint = Color(0xFFFFF8F4)
private val AuthInk = Color(0xFF202137)
private val AuthMuted = Color(0xFF71758A)
private val AuthBorder = Color(0xFFDEDFe7)

@Composable
fun AuthScreen(
    state: AuthUiState,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onReset: (String) -> Unit,
    onGoogleToken: (String) -> Unit,
    onGoogleError: (String) -> Unit,
    onConfirmPasswordReset: (String, String) -> Unit,
    onClosePasswordReset: () -> Unit,
    onClearFeedback: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(AuthMode.Login) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    if (state.passwordResetLinkState != PasswordResetLinkState.None) {
        PasswordResetLinkScreen(
            state = state,
            onConfirm = onConfirmPasswordReset,
            onClose = onClosePasswordReset,
            onClearFeedback = onClearFeedback,
        )
        return
    }

    fun show(next: AuthMode) {
        mode = next
        password = ""
        passwordVisible = false
        onClearFeedback()
    }

    BackHandler(enabled = mode != AuthMode.Login) { show(AuthMode.Login) }

    Box(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().imePadding()) {
        if (state.loading) {
            CircularProgressIndicator(color = AuthOrange, modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (mode) {
                AuthMode.Login -> LoginArtworkHeader()
                AuthMode.Register -> LearningArtworkHeader { show(AuthMode.Login) }
                AuthMode.Reset -> ResetArtworkHeader { show(AuthMode.Login) }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-13).dp)
                    .background(Color.White, RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp))
                    .padding(start = 26.dp, end = 26.dp, top = 23.dp, bottom = 22.dp),
            ) {
                val resetSent = mode == AuthMode.Reset && state.message != null && state.error == null
                AuthIntro(
                    title = when {
                        resetSent -> "Check your email"
                        mode == AuthMode.Login -> "Welcome back"
                        mode == AuthMode.Register -> "Create account"
                        else -> "Forgot password?"
                    },
                    subtitle = when {
                        resetSent -> "If an account exists for this email,\nyou’ll receive a password reset link."
                        mode == AuthMode.Login -> "Ready for your next study session?"
                        mode == AuthMode.Register -> "Your learning journey starts here."
                        else -> "Enter your email to get a reset link."
                    },
                )

                if (resetSent) {
                    Text(
                        email,
                        color = AuthNavy,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().background(AuthSoft, RoundedCornerShape(12.dp)).padding(15.dp),
                    )
                    Spacer(Modifier.height(22.dp))
                    PrimaryAction("Back to log in", false) { show(AuthMode.Login) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Wrong email?", color = AuthMuted, fontSize = 12.sp)
                        TextButton(onClick = onClearFeedback) {
                            Text("Try another address", color = AuthOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                } else {
                    if (mode == AuthMode.Register) {
                        AuthField("Name", name, { name = it; onClearFeedback() }, "Your name", Icons.Rounded.Person, KeyboardType.Text, ImeAction.Next)
                        Spacer(Modifier.height(14.dp))
                    }

                    AuthField(
                        if (mode == AuthMode.Register) "Email" else "Email address",
                        email,
                        { email = it; onClearFeedback() },
                        "you@example.com",
                        Icons.Rounded.Email,
                        KeyboardType.Email,
                        if (mode == AuthMode.Reset) ImeAction.Done else ImeAction.Next,
                    )

                    if (mode != AuthMode.Reset) {
                        Spacer(Modifier.height(14.dp))
                        AuthField(
                            label = "Password",
                            value = password,
                            onValueChange = { password = it; onClearFeedback() },
                            placeholder = if (mode == AuthMode.Register) "At least 8 characters" else "Enter your password",
                            icon = Icons.Rounded.Lock,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            passwordVisible = passwordVisible,
                            onPasswordVisibility = { passwordVisible = !passwordVisible },
                        )
                    }

                    if (mode == AuthMode.Login) {
                        Row(Modifier.fillMaxWidth().padding(top = 1.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { show(AuthMode.Reset) }) {
                                Text("Forgot password?", color = AuthOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    } else {
                        Spacer(Modifier.height(if (mode == AuthMode.Reset) 23.dp else 18.dp))
                    }

                    Feedback(state)
                    PrimaryAction(
                        label = when (mode) {
                            AuthMode.Login -> "Log in"
                            AuthMode.Register -> "Create account"
                            AuthMode.Reset -> "Send reset link"
                        },
                        loading = state.submitting,
                        onClick = {
                            when (mode) {
                                AuthMode.Login -> onSignIn(email, password)
                                AuthMode.Register -> onRegister(name, email, password)
                                AuthMode.Reset -> onReset(email)
                            }
                        },
                    )

                    if (mode != AuthMode.Reset) {
                        OrDivider()
                        GoogleButton(!state.submitting, onGoogleToken, onGoogleError)
                    }
                    AuthFooter(mode, { show(AuthMode.Login) }, { show(AuthMode.Register) })
                    if (mode == AuthMode.Register) {
                        Text(
                            "By creating an account, you agree to our Terms of Service and Privacy Policy.",
                            color = AuthMuted,
                            fontSize = 10.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordResetLinkScreen(
    state: AuthUiState,
    onConfirm: (String, String) -> Unit,
    onClose: () -> Unit,
    onClearFeedback: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ResetArtworkHeader(onClose)

            val title = when (state.passwordResetLinkState) {
                PasswordResetLinkState.Verifying -> "Checking link"
                PasswordResetLinkState.Ready -> "Create new password"
                PasswordResetLinkState.Invalid -> "Link expired"
                PasswordResetLinkState.Complete -> "Password changed"
                PasswordResetLinkState.None -> "Reset password"
            }
            val subtitle = when (state.passwordResetLinkState) {
                PasswordResetLinkState.Verifying -> "Making sure this reset link is valid and secure."
                PasswordResetLinkState.Ready -> state.passwordResetEmail
                    ?.let { "Choose a new password for $it." }
                    ?: "Choose a strong password you haven’t used before."
                PasswordResetLinkState.Invalid -> "This link can no longer be used. Request a fresh one to protect your account."
                PasswordResetLinkState.Complete -> "Your account is secure with the new password."
                PasswordResetLinkState.None -> ""
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-13).dp)
                    .background(Color.White, RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp))
                    .padding(start = 26.dp, end = 26.dp, top = 23.dp, bottom = 24.dp),
            ) {
                AuthIntro(title, subtitle)

                when (state.passwordResetLinkState) {
                    PasswordResetLinkState.Verifying -> {
                        CircularProgressIndicator(color = AuthOrange, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(38.dp))
                    }
                    PasswordResetLinkState.Ready -> {
                        AuthField(
                            label = "New password",
                            value = password,
                            onValueChange = { password = it; onClearFeedback() },
                            placeholder = "At least 8 characters",
                            icon = Icons.Rounded.Lock,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                            passwordVisible = passwordVisible,
                            onPasswordVisibility = { passwordVisible = !passwordVisible },
                        )
                        Spacer(Modifier.height(14.dp))
                        AuthField(
                            label = "Confirm password",
                            value = confirmation,
                            onValueChange = { confirmation = it; onClearFeedback() },
                            placeholder = "Enter it again",
                            icon = Icons.Rounded.Lock,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            passwordVisible = passwordVisible,
                            onPasswordVisibility = { passwordVisible = !passwordVisible },
                        )
                        Spacer(Modifier.height(20.dp))
                        Feedback(state)
                        PrimaryAction(
                            label = "Change password",
                            loading = state.submitting,
                            onClick = { onConfirm(password, confirmation) },
                        )
                    }
                    PasswordResetLinkState.Invalid -> {
                        Feedback(state)
                        PrimaryAction("Request a new link", false, onClose)
                    }
                    PasswordResetLinkState.Complete -> {
                        Feedback(state)
                        PrimaryAction("Back to log in", false, onClose)
                    }
                    PasswordResetLinkState.None -> Unit
                }
            }
        }
    }
}

@Composable
private fun Brand(onDark: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(if (onDark) 25.dp else 31.dp).background(AuthOrange, RoundedCornerShape(if (onDark) 8.dp else 10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Z", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = if (onDark) 13.sp else 16.sp)
        }
        Text(
            "Zen Maestro",
            color = if (onDark) Color.White else AuthNavy,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (onDark) 14.sp else 16.sp,
            modifier = Modifier.padding(start = if (onDark) 6.dp else 9.dp),
        )
    }
}

@Composable
private fun BackButton(onDark: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(if (onDark) 36.dp else 40.dp)
            .background(
                if (onDark) Color.White.copy(alpha = 0.04f) else Color.White,
                RoundedCornerShape(11.dp),
            )
            .then(
                if (onDark) Modifier.border(
                    1.dp,
                    Color.White.copy(alpha = 0.14f),
                    RoundedCornerShape(11.dp),
                ) else Modifier,
            ),
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back to log in",
            tint = if (onDark) Color.White else TextPrimary,
        )
    }
}

@Composable
private fun LoginArtworkHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(228.dp)
            .background(Brush.radialGradient(listOf(AuthNavyGlow, AuthNavy), radius = 720f))
            .padding(start = 24.dp, end = 24.dp, top = 20.dp),
    ) {
        Brand(onDark = true)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(181.dp)) {
            ArtworkRings(Modifier.align(Alignment.Center).size(143.dp), dark = true)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 1.dp, y = 16.dp)
                    .width(240.dp)
                    .graphicsLayer(rotationZ = -5f)
                    .background(Color.White, RoundedCornerShape(15.dp))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(35.dp).background(AuthSoft, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = AuthOrange, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("Study databases", color = AuthInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Today · 10:00 AM", color = AuthMuted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("STUDY", color = AuthOrange, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(AuthSoft, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 4.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 9.dp), color = Color(0xFFEFEdf5))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Learning plan", color = AuthMuted, fontSize = 9.sp)
                    Text("Ready to start", color = AuthMuted, fontSize = 9.sp)
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(y = (-8).dp)
                    .width(197.dp)
                    .graphicsLayer(rotationZ = 4f)
                    .background(Color.White, RoundedCornerShape(14.dp))
                    .padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Timer, null, tint = AuthOrange, modifier = Modifier.size(32.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Time to focus", color = AuthInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("25 min · One task at a time", color = AuthMuted, fontSize = 9.sp)
                }
            }
            Icon(Icons.Outlined.AutoAwesome, null, tint = AuthOrange, modifier = Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 7.dp).size(23.dp))
        }
    }
}

@Composable
private fun LearningArtworkHeader(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(199.dp)
            .background(Brush.radialGradient(listOf(AuthNavyGlow, AuthNavy), radius = 720f))
            .padding(horizontal = 23.dp, vertical = 15.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BackButton(onDark = true, onClick = onBack)
            Brand(onDark = true)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(133.dp),
        ) {
            Canvas(Modifier.align(Alignment.Center).size(132.dp)) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.09f),
                    radius = size.minDimension * 0.35f,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f)),
                    ),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (-43).dp, y = (-1).dp)
                    .size(width = 129.dp, height = 99.dp)
                    .graphicsLayer(rotationZ = -7f)
                    .background(Color(0xFFFFFAF6), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = AuthOrange,
                    modifier = Modifier.size(66.dp),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-23).dp, y = 12.dp)
                    .size(33.dp)
                    .graphicsLayer(rotationZ = 9f)
                    .background(AuthOrange, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-18).dp, y = (-5).dp)
                    .graphicsLayer(rotationZ = 4f)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .size(width = 119.dp, height = 58.dp)
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = AuthOrange,
                    modifier = Modifier.size(27.dp),
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProgressBar(9.dp, 1f)
                    ProgressBar(14.dp, 0.65f)
                    ProgressBar(20.dp, 0.35f)
                }
            }

            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 17.dp, y = (-15).dp)
                    .size(5.dp)
                    .background(AuthOrange, CircleShape),
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-3).dp)
                    .size(4.dp)
                    .background(Color.White.copy(alpha = 0.45f), CircleShape),
            )
        }
    }
}

@Composable
private fun ResetArtworkHeader(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(242.dp)
            .background(Brush.radialGradient(listOf(AuthNavyGlow, AuthNavy), radius = 720f))
            .padding(horizontal = 23.dp, vertical = 15.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            BackButton(onDark = true, onClick = onBack)
            Brand(onDark = true)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(165.dp)) {
            ArtworkRings(Modifier.align(Alignment.Center).size(156.dp), dark = true)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 104.dp, height = 87.dp)
                    .graphicsLayer(rotationZ = -7f)
                    .background(Color(0xFFFFF7F1), RoundedCornerShape(17.dp))
                    .border(1.dp, Color(0xFFF5D9C8), RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Email, null, tint = AuthOrange, modifier = Modifier.size(43.dp))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-47).dp, y = (-22).dp)
                    .size(40.dp)
                    .graphicsLayer(rotationZ = 8f)
                    .background(AuthOrange, RoundedCornerShape(12.dp))
                    .border(3.dp, AuthNavy, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun ArtworkRings(modifier: Modifier, dark: Boolean) {
    Canvas(modifier) {
        val color = if (dark) Color.White.copy(alpha = 0.10f) else Color(0xFFEFDBCE)
        drawCircle(color = color, style = Stroke(width = 1.dp.toPx()))
        drawCircle(
            color = color,
            radius = size.minDimension * 0.35f,
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))),
        )
    }
}

@Composable
private fun ProgressBar(height: androidx.compose.ui.unit.Dp, alpha: Float) {
    Box(
        Modifier
            .size(width = 6.dp, height = height)
            .background(AuthOrange.copy(alpha = alpha), RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun AuthIntro(title: String, subtitle: String) {
    Text(title, color = AuthInk, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
    Text(subtitle, color = AuthMuted, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 7.dp, bottom = 20.dp))
}

@Composable
private fun AuthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    passwordVisible: Boolean? = null,
    onPasswordVisibility: (() -> Unit)? = null,
) {
    Column {
        Text(
            label,
            color = AuthInk,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, bottom = 9.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = AuthMuted) },
            leadingIcon = { Icon(icon, contentDescription = null, tint = AuthMuted, modifier = Modifier.size(17.dp)) },
            trailingIcon = if (passwordVisible != null && onPasswordVisibility != null) {
                {
                    IconButton(onClick = onPasswordVisibility) {
                        Icon(
                            if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = AuthMuted,
                        )
                    }
                }
            } else null,
            visualTransformation = if (passwordVisible == false) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AuthOrange,
                unfocusedBorderColor = AuthBorder,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                cursorColor = AuthOrange,
                focusedTextColor = AuthInk,
                unfocusedTextColor = AuthInk,
            ),
            modifier = Modifier.fillMaxWidth().height(53.dp),
        )
    }
}

@Composable
private fun Feedback(state: AuthUiState) {
    val message = state.error ?: state.message ?: return
    Text(
        text = message,
        color = if (state.error != null) Danger else Success,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 3.dp, bottom = 14.dp),
    )
}

@Composable
private fun PrimaryAction(label: String, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !loading,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AuthOrange,
            contentColor = Color.White,
            disabledContainerColor = AuthOrange.copy(alpha = 0.55f),
            disabledContentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().height(53.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        } else {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.padding(start = 9.dp).size(17.dp))
        }
    }
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFECECF2))
        Text("or", color = AuthMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFECECF2))
    }
}

@Composable
private fun GoogleButton(enabled: Boolean, onToken: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requesting by remember { mutableStateOf(false) }

    Button(
        onClick = {
            scope.launch {
                requesting = true
                try {
                    val clientIdResource = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                    if (clientIdResource == 0) {
                        onError("Google sign-in isn't ready yet. Try email and password for now.")
                        return@launch
                    }
                    val option = GetSignInWithGoogleOption.Builder(context.getString(clientIdResource)).build()
                    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                    val result = CredentialManager.create(context).getCredential(
                        context = context.findActivity() ?: context,
                        request = request,
                    )
                    val credential = result.credential
                    if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        onToken(GoogleIdTokenCredential.createFrom(credential.data).idToken)
                    } else {
                        onError("Google sign-in couldn't verify this account. Try again.")
                    }
                } catch (_: GetCredentialCancellationException) {
                    // Closing the Google account picker is not an authentication failure.
                } catch (_: GetCredentialException) {
                    onError("Google sign-in couldn't be completed. Try again in a moment.")
                } catch (_: Exception) {
                    onError("Google sign-in couldn't be completed. Try again.")
                } finally {
                    requesting = false
                }
            }
        },
        enabled = enabled && !requesting,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Card,
            contentColor = AuthInk,
            disabledContainerColor = Card,
            disabledContentColor = AuthMuted,
        ),
        border = ButtonDefaults.outlinedButtonBorder(enabled = enabled && !requesting),
        modifier = Modifier.fillMaxWidth().height(51.dp),
    ) {
        if (requesting) {
            CircularProgressIndicator(color = AuthOrange, strokeWidth = 2.dp, modifier = Modifier.size(23.dp))
        } else {
            Text("G", color = Color(0xFF4285F4), fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            Text("Continue with Google", modifier = Modifier.padding(start = 10.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AuthFooter(mode: AuthMode, onLogin: () -> Unit, onRegister: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (mode) {
            AuthMode.Login -> {
                Text("New here?", color = AuthMuted, fontSize = 12.sp)
                TextButton(onClick = onRegister) { Text("Create account", color = AuthOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            AuthMode.Register -> {
                Text("Already a member?", color = AuthMuted, fontSize = 12.sp)
                TextButton(onClick = onLogin) { Text("Log in", color = AuthOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            AuthMode.Reset -> {
                Text("Remembered it?", color = AuthMuted, fontSize = 12.sp)
                TextButton(onClick = onLogin) { Text("Back to log in", color = AuthOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
