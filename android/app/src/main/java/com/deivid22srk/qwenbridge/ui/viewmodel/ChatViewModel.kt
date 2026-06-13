package com.deivid22srk.qwenbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.qwenbridge.client.QwenClient
import com.deivid22srk.qwenbridge.client.QwenStreamEvent
import com.deivid22srk.qwenbridge.database.AccountDao
import com.deivid22srk.qwenbridge.database.SessionMappingDao
import com.deivid22srk.qwenbridge.database.SessionMappingEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ChatUiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant"
    val content: String,
    val reasoning: String? = null,
    val isTyping: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val accountDao: AccountDao,
    private val sessionMappingDao: SessionMappingDao,
    private val qwenClient: QwenClient
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages.asStateFlow()

    private val _selectedModel = MutableStateFlow("qwen3.7-plus")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Chave única para a conversa local atual para persistir o histórico no Qwen
    private var localConversationKey = UUID.randomUUID().toString()

    val availableModels = listOf(
        "qwen3.7-plus", "qwen3.7-max", "qwen3.6-plus", "qwen3.5-plus", "qwen3.5-flash", "qwen3-coder-plus"
    )

    fun selectModel(model: String) {
        _selectedModel.value = model
    }

    fun clearChat() {
        _messages.value = emptyList()
        localConversationKey = UUID.randomUUID().toString()
        _errorMessage.value = null
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return

        val userMessage = ChatUiMessage(role = "user", content = text)
        _messages.value = _messages.value + userMessage
        _errorMessage.value = null
        _isGenerating.value = true

        viewModelScope.launch {
            val accounts = accountDao.getAllAccounts()
            val activeAccount = accounts.firstOrNull { it.isActive }
            if (activeAccount == null) {
                _errorMessage.value = "Nenhuma conta ativa para enviar mensagem. Faça login no Dashboard."
                _isGenerating.value = false
                return@launch
            }

            // Recupera a sessão atual mapeada no banco
            val mapping = sessionMappingDao.getMapping(localConversationKey)
            var chatId = mapping?.chatSessionId
            var parentId = mapping?.parentMessageId

            // Se for nova conversa no Qwen, cria uma sessão física
            if (chatId.isNullOrBlank()) {
                chatId = qwenClient.createChatSession(
                    cookie = activeAccount.cookies,
                    userAgent = activeAccount.userAgent,
                    model = _selectedModel.value
                )
                if (chatId.isNullOrBlank()) {
                    _errorMessage.value = "Falha ao criar sessão de conversa no Qwen."
                    _isGenerating.value = false
                    return@launch
                }
                parentId = null
                sessionMappingDao.insertMapping(
                    SessionMappingEntity(
                        conversationKey = localConversationKey,
                        chatSessionId = chatId,
                        parentMessageId = null
                    )
                )
            }

            // Formata o prompt
            // Se for nova conversa, enviamos toda a história formatada, senão apenas o texto atual
            val promptText = if (mapping == null) {
                formatHistoryForPrompt()
            } else {
                "User: $text\n\n"
            }

            val enableThinking = !_selectedModel.value.endsWith("-no-thinking")

            // Adiciona o balão vazio do assistente para streaming
            val assistantMessageId = UUID.randomUUID().toString()
            var assistantContent = ""
            var assistantReasoning = ""

            val initialAssistantMsg = ChatUiMessage(
                id = assistantMessageId,
                role = "assistant",
                content = "",
                reasoning = if (enableThinking) "" else null,
                isTyping = true
            )
            _messages.value = _messages.value + initialAssistantMsg

            try {
                qwenClient.sendChatCompletion(
                    cookie = activeAccount.cookies,
                    userAgent = activeAccount.userAgent,
                    model = _selectedModel.value,
                    chatId = chatId,
                    parentId = parentId,
                    prompt = promptText,
                    enableThinking = enableThinking
                ).collect { event ->
                    when (event) {
                        is QwenStreamEvent.Content -> {
                            assistantContent += event.text
                            updateAssistantMessage(assistantMessageId, assistantContent, assistantReasoning)
                        }
                        is QwenStreamEvent.Thinking -> {
                            assistantReasoning += event.text
                            updateAssistantMessage(assistantMessageId, assistantContent, assistantReasoning)
                        }
                        is QwenStreamEvent.SessionUpdated -> {
                            sessionMappingDao.insertMapping(
                                SessionMappingEntity(
                                    conversationKey = localConversationKey,
                                    chatSessionId = event.chatId,
                                    parentMessageId = event.messageId
                                )
                            )
                        }
                        is QwenStreamEvent.Error -> {
                            _errorMessage.value = event.message
                            _isGenerating.value = false
                        }
                        is QwenStreamEvent.Done -> {
                            // Concluído, remove o indicador de digitando
                            val currentList = _messages.value
                            _messages.value = currentList.map {
                                if (it.id == assistantMessageId) it.copy(isTyping = false) else it
                            }
                            _isGenerating.value = false
                        }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Erro na requisição: ${e.message}"
                _isGenerating.value = false
            }
        }
    }

    private fun updateAssistantMessage(id: String, content: String, reasoning: String) {
        val currentList = _messages.value
        _messages.value = currentList.map {
            if (it.id == id) {
                it.copy(
                    content = content,
                    reasoning = if (reasoning.isNotEmpty()) reasoning else null
                )
            } else {
                it
            }
        }
    }

    private fun formatHistoryForPrompt(): String {
        return _messages.value.joinToString("") { msg ->
            when (msg.role) {
                "user" -> "User: ${msg.content}\n\n"
                "assistant" -> "Assistant: ${msg.reasoning?.let { "$it\n" } ?: ""}${msg.content}\n\n"
                else -> "${msg.content}\n\n"
            }
        }
    }
}
