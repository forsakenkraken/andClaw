package com.coderred.andclaw.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coderred.andclaw.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenClawConfigEditorScreen(
    onBack: () -> Unit,
    onNavigateDashboard: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.openClawConfigEditorState.collectAsState()
    val navigation by viewModel.openClawConfigEditorNavigation.collectAsState()
    var pendingBackupToLoad by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadOpenClawConfigEditor()
    }

    LaunchedEffect(navigation) {
        if (navigation == SettingsViewModel.OpenClawConfigEditorNavigation.NavigateDashboard) {
            viewModel.consumeOpenClawConfigEditorNavigation()
            onNavigateDashboard()
        }
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_openclaw_config_editor_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !uiState.isSaving,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_cd_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::saveOpenClawConfigEditor,
                        enabled = !uiState.isLoading && !uiState.isSaving,
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(stringResource(R.string.settings_openclaw_config_editor_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_openclaw_config_editor_current_source),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = uiState.sourceLabel
                            ?: stringResource(R.string.settings_openclaw_config_editor_missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_openclaw_config_editor_backups),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (uiState.backups.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_openclaw_config_editor_no_backups),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            uiState.backups.forEach { backup ->
                                FilledTonalButton(
                                    onClick = {
                                        if (uiState.text != uiState.baselineText) {
                                            pendingBackupToLoad = backup
                                        } else {
                                            viewModel.loadOpenClawConfigBackup(backup)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !uiState.isLoading && !uiState.isSaving,
                                ) {
                                    Text(backup)
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.isConfigMissing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.settings_openclaw_config_editor_missing),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (uiState.errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_openclaw_config_editor_save_failed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = uiState.text,
                onValueChange = viewModel::updateOpenClawConfigEditorText,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enabled = !uiState.isSaving,
                minLines = 16,
            )

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    if (pendingBackupToLoad != null) {
        AlertDialog(
            onDismissRequest = { pendingBackupToLoad = null },
            title = { Text(stringResource(R.string.settings_openclaw_config_editor_replace_confirm_title)) },
            text = { Text(stringResource(R.string.settings_openclaw_config_editor_replace_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val backup = pendingBackupToLoad
                        pendingBackupToLoad = null
                        if (backup != null) {
                            viewModel.loadOpenClawConfigBackup(backup)
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBackupToLoad = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
