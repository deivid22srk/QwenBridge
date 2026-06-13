package com.deivid22srk.qwenbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.qwenbridge.database.AccountDao
import com.deivid22srk.qwenbridge.database.AccountEntity
import com.deivid22srk.qwenbridge.database.LogDao
import com.deivid22srk.qwenbridge.database.LogEntity
import com.deivid22srk.qwenbridge.database.SessionMappingDao
import com.deivid22srk.qwenbridge.server.QwenBridgeServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerViewModel @Inject constructor(
    private val accountDao: AccountDao,
    private val logDao: LogDao,
    private val sessionMappingDao: SessionMappingDao,
    private val qwenBridgeServer: QwenBridgeServer
) : ViewModel() {

    val isServerRunning: StateFlow<Boolean> = qwenBridgeServer.isServerRunning
    val serverPort: StateFlow<Int> = qwenBridgeServer.serverPort

    val accounts: StateFlow<List<AccountEntity>> = accountDao.getAllAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntity>> = logDao.getRecentLogsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleServer(port: Int) {
        if (isServerRunning.value) {
            qwenBridgeServer.stop()
        } else {
            qwenBridgeServer.start(port)
        }
    }

    fun deleteAccount(email: String) {
        viewModelScope.launch {
            accountDao.deleteAccount(email)
        }
    }

    fun setAccountActive(email: String, active: Boolean) {
        viewModelScope.launch {
            val account = accountDao.getAccountByEmail(email)
            if (account != null) {
                // Desativa todas as outras se essa estiver sendo ativada
                if (active) {
                    val all = accountDao.getAllAccounts()
                    all.forEach {
                        if (it.email != email && it.isActive) {
                            accountDao.insertAccount(it.copy(isActive = false))
                        }
                    }
                }
                accountDao.insertAccount(account.copy(isActive = active))
            }
        }
    }

    fun saveAccount(email: String, cookies: String, userAgent: String) {
        viewModelScope.launch {
            // Se for a primeira conta, marca como ativa por padrão
            val all = accountDao.getAllAccounts()
            val isActive = all.isEmpty()
            accountDao.insertAccount(
                AccountEntity(
                    email = email,
                    cookies = cookies,
                    userAgent = userAgent,
                    isActive = isActive,
                    lastLoggedIn = System.currentTimeMillis()
                )
            )
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logDao.clearLogs()
        }
    }

    fun clearSessionMappings() {
        viewModelScope.launch {
            sessionMappingDao.clearMappings()
        }
    }
}
