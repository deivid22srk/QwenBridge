package com.deivid22srk.qwenbridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deivid22srk.qwenbridge.database.AccountEntity
import com.deivid22srk.qwenbridge.database.LogEntity
import com.deivid22srk.qwenbridge.ui.theme.*
import com.deivid22srk.qwenbridge.ui.viewmodel.ServerViewModel
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    viewModel: ServerViewModel,
    onNavigateToLogin: () -> Unit
) {
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val logs by viewModel.logs.collectAsState()

    var portInput by remember { mutableStateOf("3000") }

    LaunchedEffect(serverPort) {
        portInput = serverPort.toString()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- CABEÇALHO ---
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "QwenBridge",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    ),
                    color = Color.White
                )
                Text(
                    text = "Proxy local compatível com OpenAI no Android",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        // --- PAINEL DO SERVIDOR LOCAL (KTOR) ---
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Servidor Ktor",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            if (isServerRunning) NeonCyan else Color.Red,
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isServerRunning) "Executando em http://localhost:$serverPort" else "Parado",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isServerRunning) NeonCyan else Color.Red
                                )
                            }
                        }

                        Switch(
                            checked = isServerRunning,
                            onCheckedChange = {
                                val port = portInput.toIntOrNull() ?: 3000
                                viewModel.toggleServer(port)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PurplePrimary,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = BackgroundDark
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it },
                            label = { Text("Porta") },
                            enabled = !isServerRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                disabledTextColor = TextMuted,
                                focusedBorderColor = PurplePrimary,
                                unfocusedBorderColor = BorderLight,
                                disabledBorderColor = BorderLight.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        )
                    }
                }
            }
        }

        // --- GERENCIAMENTO DE CONTAS ---
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Contas Qwen Conectadas",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        IconButton(
                            onClick = onNavigateToLogin,
                            modifier = Modifier
                                .size(36.dp)
                                .background(PurplePrimary, CircleShape)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Adicionar Conta", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (accounts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = TextMuted)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Nenhuma conta vinculada.",
                                    color = TextMuted,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    } else {
                        accounts.forEach { account ->
                            AccountRow(
                                account = account,
                                onToggleActive = { active -> viewModel.setAccountActive(account.email, active) },
                                onDelete = { viewModel.deleteAccount(account.email) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        // --- CONSOLE DE LOGS ---
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Console de Logs do Servidor",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Row {
                            IconButton(onClick = { viewModel.clearLogs() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Limpar logs", tint = TextSecondary)
                            }
                            IconButton(onClick = { viewModel.clearSessionMappings() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Resetar Sessões Qwen", tint = TextSecondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LogConsole(logs = logs)
                }
            }
        }
    }
}

@Composable
fun AccountRow(
    account: AccountEntity,
    onToggleActive: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundDark, RoundedCornerShape(12.dp))
            .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.email,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White
            )
            Text(
                text = if (account.isActive) "Conta Ativa" else "Inativa",
                style = MaterialTheme.typography.labelSmall,
                color = if (account.isActive) NeonCyan else TextMuted
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = account.isActive,
                onCheckedChange = onToggleActive,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = NeonCyan,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = BackgroundDark
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Excluir conta", tint = Color.Red.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
fun LogConsole(logs: List<LogEntity>) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(0)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color.Black, RoundedCornerShape(8.dp))
            .border(1.dp, BorderLight, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true
        ) {
            items(logs) { log ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    val levelColor = when (log.level) {
                        "ERROR" -> Color.Red
                        "WARN" -> Color(0xFFFFA500)
                        "INFO" -> NeonCyan
                        else -> Color.LightGray
                    }
                    Text(
                        text = "[${log.level}]",
                        color = levelColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(55.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = log.message,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
