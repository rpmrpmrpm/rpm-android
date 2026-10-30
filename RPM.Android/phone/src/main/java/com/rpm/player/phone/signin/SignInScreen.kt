package com.rpm.player.phone.signin

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.widget.ProgressBar
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rpm.player.core.R
import com.rpm.player.core.auth.Provider
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.GlassField
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.brand
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.system
import com.rpm.player.phone.ui.RecordDisc
import com.rpm.player.phone.ui.Wordmark
import com.rpm.player.phone.ui.WordmarkView

/**
 * The sign-in gate. It matches the iPhone's `SignInView` (RPM.iOS/rpm/SignInView.swift), layer for layer.
 * When signed out there is nothing else to show, so this is the first screen, not a menu item.
 *
 * The record is cut off by the right edge, half off screen. The card is live glass over it. The rest of the
 * app uses the same layering.
 */
@Composable
fun SignInScreen(signIn: SignIn) {
    val backdrop = rememberBackdrop()
    val context = LocalContext.current
    val signUp = signIn.signUp
    val step = signUp.step

    // Back from sign-up returns to the sign-in card, as its Cancel does.
    BackHandler(enabled = step != null) { signUp.close() }

    // The address arrives after a request. Whichever activity is on screen then opens it.
    val address = signIn.toOpen
    LaunchedEffect(address) {
        if (address == null) return@LaunchedEffect
        val shown = openInBrowser(context, address)
        signIn.opened()
        // No browser could open it. The person is still in the app, so the watch checks once and stops.
        if (!shown) signIn.backFromBrowser()
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().backdropSource(backdrop).background(Ink.ground)) {
            // The record hangs half off the right edge, as on the desktop's artwork band. It turns from the
            // start, because a still record would look broken.
            val diameter = maxWidth * 0.9f
            RecordDisc(diameter, Modifier.align(Alignment.CenterEnd).offset(x = diameter / 2))
            // A dark wash over the record, so that white text can be read on it. The record stays visible.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Ink.ground.copy(alpha = 0xCC / 255f),
                        0.55f to Ink.ground.copy(alpha = 0x99 / 255f),
                        1f to Ink.ground.copy(alpha = 0xE6 / 255f),
                    ),
                ),
            )
        }

        // Centred in the space that is left. It scrolls, so the fields can move above the keyboard.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val viewport = maxHeight
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .heightIn(min = viewport)
                        .widthIn(max = 420.dp)
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WordmarkView(Color.White, Modifier.width(150.dp).height(150.dp / Wordmark.aspect))
                    BasicText(
                        stringResource(if (step == null) R.string.Rpm_SignIn_Subtitle else R.string.SignUp_Subtitle),
                        Modifier.widthIn(max = 320.dp),
                        style = system(15f).copy(color = Ink.white(0xCC), textAlign = TextAlign.Center),
                    )
                    if (step != null) {
                        SignUpCard(signUp, step, backdrop)
                    } else {
                        Card(signIn, backdrop)
                        BasicText(
                            stringResource(R.string.Login_NewHere),
                            Modifier.widthIn(max = 320.dp),
                            style = system(13f).copy(color = Color(0xFFCCDDEE).copy(alpha = 0xAA / 255f), textAlign = TextAlign.Center),
                        )
                        GlassButton(onClick = { signUp.open() }, modifier = Modifier.widthIn(max = 320.dp), enabled = !signIn.busy) { style ->
                            BasicText(stringResource(R.string.SignUp_Button), style = style)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(signIn: SignIn, backdrop: Backdrop) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val passwordField = remember { FocusRequester() }
    val enabled = !signIn.busy

    GlassCard(backdrop, Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(stringResource(R.string.Login_ContinueWith), style = kicker())
            Social(stringResource(R.string.Login_Apple), Glyph.apple, enabled) { signIn.withProvider(Provider.APPLE) }
            Social(stringResource(R.string.Login_Google), Glyph.google, enabled) { signIn.withProvider(Provider.GOOGLE) }
            Divider()
            GlassField(
                value = email,
                onValueChange = { email = it },
                placeholder = stringResource(R.string.Login_Email),
                // A password manager fills the pair, as iOS's `textContentType(.username)` and `.password` offer.
                modifier = Modifier.semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { passwordField.requestFocus() }),
            )
            GlassField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.Login_Password),
                modifier = Modifier.focusRequester(passwordField).semantics { contentType = ContentType.Password },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { signIn.withPassword(email, password) }),
                visualTransformation = PasswordVisualTransformation(),
            )
            Line(signIn)
            GlassButton(onClick = { signIn.withPassword(email, password) }, weight = Weight.Primary, enabled = enabled) { style ->
                BasicText(stringResource(R.string.Cloud_SignIn), style = style)
            }
            if (signIn.busy) Busy()
        }
    }
}

/**
 * Making an account, in place of the sign-in card. The same steps as the iPhone's `SignUpCard`: the form, then the
 * code from the mail. Once the code is taken the new account signs in, and the app replaces this screen.
 */
@Composable
private fun SignUpCard(signUp: SignUp, step: SignUp.Step, backdrop: Backdrop) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val emailField = remember { FocusRequester() }
    val passwordField = remember { FocusRequester() }
    val enabled = !signUp.busy

    GlassCard(backdrop, Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(stringResource(R.string.SignUp_Title), style = kicker())
            when (step) {
                SignUp.Step.Form -> {
                    GlassField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = stringResource(R.string.SignUp_Name),
                        modifier = Modifier.semantics { contentType = ContentType.PersonFullName },
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { emailField.requestFocus() }),
                    )
                    GlassField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = stringResource(R.string.Login_Email),
                        modifier = Modifier.focusRequester(emailField)
                            .semantics { contentType = ContentType.NewUsername + ContentType.EmailAddress },
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onNext = { passwordField.requestFocus() }),
                    )
                    GlassField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = stringResource(R.string.Login_Password),
                        modifier = Modifier.focusRequester(passwordField).semantics { contentType = ContentType.NewPassword },
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { signUp.create(name, email, password) }),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    BasicText(
                        stringResource(R.string.SignUp_PasswordRule),
                        Modifier.fillMaxWidth(),
                        style = system(12f).copy(color = Ink.white(0x77)),
                    )
                }
                is SignUp.Step.Code -> {
                    BasicText(
                        stringResource(R.string.SignUp_CodeSent, step.email),
                        Modifier.fillMaxWidth(),
                        style = system(13f).copy(color = Ink.white(0xCC), textAlign = TextAlign.Center),
                    )
                    GlassField(
                        value = code,
                        onValueChange = { typed -> code = typed.filter { it.isDigit() }.take(6) },
                        placeholder = stringResource(R.string.SignUp_Code),
                        modifier = Modifier.semantics { contentType = ContentType.SmsOtpCode },
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { signUp.confirm(code) }),
                    )
                    GlassButton(onClick = { signUp.resend() }, enabled = enabled) { style ->
                        BasicText(stringResource(R.string.SignUp_Resend), style = system(13f).copy(color = style.color))
                    }
                }
            }
            SignUpLine(signUp)
            GlassButton(
                onClick = { if (step is SignUp.Step.Code) signUp.confirm(code) else signUp.create(name, email, password) },
                weight = Weight.Primary,
                enabled = enabled,
            ) { style ->
                BasicText(stringResource(if (step is SignUp.Step.Code) R.string.SignUp_Confirm else R.string.SignUp_Create), style = style)
            }
            GlassButton(onClick = { signUp.close() }, enabled = enabled) { style ->
                BasicText(stringResource(R.string.Common_Cancel), style = style)
            }
            if (signUp.busy) Busy()
        }
    }
}

/** What went wrong on the sign-up card; else news, such as a new code sent. */
@Composable
private fun SignUpLine(signUp: SignUp) {
    val (said, color) = signUp.trouble?.let { it to Ink.red } ?: signUp.notice?.let { it to Ink.white(0xCC) } ?: return
    BasicText(stringResource(said.id, *said.args.toTypedArray()), Modifier.fillMaxWidth(), style = system(13f).copy(color = color))
}

/** Shows what went wrong; else news such as a deleted account; else what is being waited for. The iPhone's order. */
@Composable
private fun Line(signIn: SignIn) {
    val trouble = signIn.trouble
    val (text, color) = when {
        trouble != null -> stringResource(trouble.id, *trouble.args.toTypedArray()) to Ink.red
        signIn.notice != null -> signIn.notice!!.let { stringResource(it.id, *it.args.toTypedArray()) } to Ink.white(0xCC)
        signIn.waitingForBrowser -> stringResource(R.string.Login_WaitingBrowser) to Ink.white(0xCC)
        else -> return
    }
    BasicText(text, Modifier.fillMaxWidth(), style = system(13f).copy(color = color))
}

@Composable
private fun Social(name: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
    GlassButton(onClick = onClick, enabled = enabled) { style ->
        BasicText(glyph, style = brand(16f).copy(color = style.color))
        Spacer(Modifier.width(10.dp))
        BasicText(name, style = style)
    }
}

/**
 * A hairline with the word in a gap in the middle. It is drawn as two lines. One line with a background patch
 * painted over it would show as a dark chip on the glass.
 */
@Composable
private fun Divider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Ink.white(0x26)))
        Spacer(Modifier.width(10.dp))
        BasicText(stringResource(R.string.Login_Or), style = system(12f).copy(color = Ink.white(0x88)))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Ink.white(0x26)))
    }
}

/** The platform's own spinner. The iPhone app shows its `ProgressView` in this place. */
@Composable
private fun Busy() {
    AndroidView(
        factory = { context ->
            ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                indeterminateTintList = ColorStateList.valueOf(android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF))
            }
        },
        modifier = Modifier.size(24.dp),
    )
}

/**
 * Opens the accounts service's sign-in page in a Custom Tab, in the app's task. The app can then come back
 * over it when the sign-in finishes. Returns false when no browser can open the page.
 */
private fun openInBrowser(context: Context, address: String): Boolean =
    try {
        CustomTabsIntent.Builder()
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(address))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
