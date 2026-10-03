package app.brix.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brix.streaming.kick.KickLoginState
import app.brix.streaming.twitch.TwitchLoginState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch

@Composable
fun IntegrationsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsState()
    val twitch = settings.twitch
    val session = viewModel.twitch
    val login by session.login.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverResult by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(stringResource(R.string.settings_cat_integrations)) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SettingsSectionHeader("Twitch") }
            item {
                BrixCard {
                    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val status = when {
                            !session.configured -> stringResource(R.string.twitch_not_configured)
                            twitch.loggedIn -> stringResource(R.string.twitch_logged_in, twitch.login)
                            else -> stringResource(R.string.twitch_logged_out)
                        }
                        Text(status, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        if (twitch.needsRelogin()) {
                            Text(
                                stringResource(R.string.twitch_relogin_needed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        when (val state = login) {
                            is TwitchLoginState.WaitingForUser -> {
                                Text(stringResource(R.string.twitch_enter_code), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    state.userCode,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 28.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(state.verificationUri, style = MaterialTheme.typography.bodySmall)
                                Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUri)))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.twitch_open_browser)) }
                                OutlinedButton(onClick = session::cancelLogin, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.btn_cancel))
                                }
                            }
                            TwitchLoginState.Starting -> Text(stringResource(R.string.twitch_starting))
                            is TwitchLoginState.Error, TwitchLoginState.Idle -> {
                                if (state is TwitchLoginState.Error) {
                                    Text(
                                        stringResource(R.string.twitch_login_failed, state.message),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (session.configured) {
                                    if (!twitch.loggedIn || twitch.needsRelogin()) {
                                        Button(onClick = session::startLogin, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.twitch_login))
                                        }
                                    }
                                    if (twitch.loggedIn) {
                                        OutlinedButton(onClick = session::logout, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.twitch_logout))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.twitch_perm_viewers),
                        subtitle = stringResource(R.string.twitch_perm_viewers_hint),
                        checked = settings.hud.showViewers,
                    ) { viewModel.updateHud(settings.hud.copy(showViewers = it)) }
                    BrixToggleRow(
                        stringResource(R.string.twitch_perm_follows),
                        subtitle = stringResource(R.string.twitch_perm_later_hint),
                        checked = twitch.wantFollows,
                    ) { viewModel.updateTwitchWants(twitch.copy(wantFollows = it)) }
                    BrixToggleRow(
                        stringResource(R.string.twitch_perm_subs),
                        subtitle = stringResource(R.string.twitch_perm_later_hint),
                        checked = twitch.wantSubs,
                    ) { viewModel.updateTwitchWants(twitch.copy(wantSubs = it)) }
                    BrixToggleRow(
                        stringResource(R.string.twitch_perm_stream_key),
                        subtitle = stringResource(R.string.twitch_perm_stream_key_hint),
                        checked = twitch.wantStreamKey,
                        divider = false,
                    ) { viewModel.updateTwitchWants(twitch.copy(wantStreamKey = it)) }
                }
            }
            if (twitch.loggedIn && twitch.grantedScopes.contains(app.brix.core.TwitchIntegration.SCOPE_STREAM_KEY)) {
                item {
                    FilledTonalButton(
                        onClick = { scope.launch { serverResult = viewModel.addTwitchServer() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.twitch_add_server)) }
                }
                serverResult?.let { ok ->
                    item {
                        Text(
                            stringResource(if (ok) R.string.twitch_server_added else R.string.twitch_server_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            kickSection(viewModel)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.kickSection(viewModel: SettingsViewModel) {
    item { SettingsSectionHeader("Kick") }
    item { KickCard(viewModel) }
}

@Composable
private fun KickCard(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val kick = settings.kick
    val session = viewModel.kick
    val login by session.login.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf(kick.clientId) }
    var secret by remember { mutableStateOf(kick.clientSecret) }
    var serverResult by remember { mutableStateOf<Boolean?>(null) }
    val successHtml = stringResource(R.string.kick_login_success_html)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    BrixCard {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.kick_setup_hint, app.brix.core.KickIntegration.REDIRECT_URI),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = {
                    clientId = it
                    viewModel.updateKick(kick.copy(clientId = it.trim()), commit = false)
                },
                label = { Text("Client ID") },
                singleLine = true,
                enabled = !kick.loggedIn,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret,
                onValueChange = {
                    secret = it
                    viewModel.updateKick(kick.copy(clientSecret = it.trim()), commit = false)
                },
                label = { Text("Client Secret") },
                singleLine = true,
                enabled = !kick.loggedIn,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (kick.loggedIn) {
                    stringResource(R.string.twitch_logged_in, kick.login.ifBlank { "?" })
                } else {
                    stringResource(R.string.twitch_logged_out)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (kick.needsRelogin()) {
                Text(
                    stringResource(R.string.twitch_relogin_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when (val state = login) {
                is KickLoginState.WaitingForBrowser -> {
                    Text(stringResource(R.string.kick_waiting_browser), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { openUrl(context, state.url) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.twitch_open_browser)) }
                    OutlinedButton(onClick = session::cancelLogin, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
                is KickLoginState.Error, KickLoginState.Idle -> {
                    if (state is KickLoginState.Error) {
                        Text(
                            stringResource(R.string.twitch_login_failed, state.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (kick.configured && (!kick.loggedIn || kick.needsRelogin())) {
                        Button(
                            onClick = {
                                viewModel.flush()
                                session.startLogin(successHtml)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.kick_login)) }
                    }
                    if (kick.loggedIn) {
                        OutlinedButton(onClick = session::logout, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.twitch_logout))
                        }
                    }
                }
            }
            LaunchedEffect(login) {
                val state = login
                if (state is KickLoginState.WaitingForBrowser) openUrl(context, state.url)
            }
        }
    }
    BrixCard {
        BrixToggleRow(
            stringResource(R.string.twitch_perm_stream_key),
            subtitle = stringResource(R.string.twitch_perm_stream_key_hint),
            checked = kick.wantStreamKey,
            divider = false,
        ) { viewModel.updateKick(kick.copy(wantStreamKey = it)) }
    }
    if (kick.loggedIn && app.brix.core.KickIntegration.SCOPE_STREAM_KEY in kick.grantedScopes) {
        FilledTonalButton(
            onClick = { scope.launch { serverResult = viewModel.addKickServer() } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.kick_add_server)) }
        serverResult?.let { ok ->
            Text(
                stringResource(if (ok) R.string.kick_server_added else R.string.twitch_server_failed),
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
