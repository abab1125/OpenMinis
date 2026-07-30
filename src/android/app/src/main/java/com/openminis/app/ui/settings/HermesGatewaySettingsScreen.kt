package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.provider.hermes.HermesClientHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B-hermes: configuration for the transparent Hermes gateway passthrough
 * (mode B). The gateway is reached through a self-hosted reverse proxy (e.g. nginx)
 * -> SSH reverse tunnel -> the local Hermes daemon on :8642, so the only credentials
 * OpenMinis needs are a base URL (pointing at the public nginx) and a
 * loopback session token. Persisted to EncryptedSharedPreferences via
 * [HermesClientHolder.saveConfig]; takes effect on the next connect.
 *
 * UI mirrors upstream's ConnectionSettingsScreen (URL + token + Test/Save),
 * trimmed to loopback-token mode (no gated username/password flow).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesGatewaySettingsScreen(
    onBack: () -> Unit,
    onNewHermesSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Seed the fields from the current config (or the default base URL).
    HermesClientHolder.init(context)
    val initial = remember { HermesClientHolder.config }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes Gateway") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Connect to the local Hermes agent. Messages in a " +
                    "Hermes-backend session transparently passthrough to the " +
                    "gateway; skills, memory and tools run on Hermes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; testResult = null },
                label = { Text("Base URL") },
                placeholder = { Text("http://your-server:8642") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it; testResult = null },
                label = { Text("Session token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        testResult = null
                        testing = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    HermesClientHolder.restApi.statusFor(baseUrl.trim(), token.trim())
                                }.getOrDefault(false)
                            }
                            testing = false
                            testResult = if (ok) "Connected ✓" else "Connection failed"
                        }
                    },
                    enabled = !testing && baseUrl.isNotBlank(),
                ) { Text("Test connection") }

                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            testResult?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Connected"))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        HermesClientHolder.saveConfig(baseUrl, token)
                        onBack()
                    },
                    enabled = baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }

                OutlinedButton(
                    onClick = {
                        HermesClientHolder.saveConfig(baseUrl, token)
                        // Mirror SessionListViewModel.createNewSession(backend="hermes"):
                        // the __hermes__ suffix on the draft id routes the new
                        // session through the Hermes gateway in ChatViewModel.
                        val draftId = "__new__${java.util.UUID.randomUUID()}__hermes__"
                        onNewHermesSession(draftId)
                    },
                    enabled = baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("New Hermes session") }
            }
        }
    }
}
