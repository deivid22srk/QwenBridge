package com.deivid22srk.qwenbridge.server

import com.deivid22srk.qwenbridge.client.QwenClient
import com.deivid22srk.qwenbridge.client.QwenStreamEvent
import com.deivid22srk.qwenbridge.database.AccountDao
import com.deivid22srk.qwenbridge.database.SessionMappingDao
import com.deivid22srk.qwenbridge.database.SessionMappingEntity
import com.deivid22srk.qwenbridge.utils.AppLogger
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import io.ktor.utils.io.writeStringUtf8
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class OpenAiModelList(
    val `object`: String = "list",
    val data: List<OpenAiModel>
)

@Serializable
data class OpenAiModel(
    val id: String,
    val `object`: String = "model",
    val created: Long = 1710000000,
    val owned_by: String = "qwen"
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    val reasoning_content: String? = null
)

@Serializable
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    val session_id: String? = null,
    val conversation_id: String? = null
)

@Serializable
data class OpenAiChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<OpenAiChoice>
)

@Serializable
data class OpenAiChoice(
    val index: Int,
    val delta: OpenAiDelta,
    val finish_reason: String? = null
)

@Serializable
data class OpenAiDelta(
    val role: String? = null,
    val content: String? = null,
    val reasoning_content: String? = null
)

@Serializable
data class OpenAiCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<OpenAiCompletionChoice>,
    val usage: OpenAiUsage
)

@Serializable
data class OpenAiCompletionChoice(
    val index: Int,
    val message: OpenAiMessage,
    val finish_reason: String = "stop"
)

@Serializable
data class OpenAiUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

data class FormattedPrompt(
    val systemPrompt: String,
    val fullPrompt: String,
    val currentPrompt: String
)

@Singleton
class QwenBridgeServer @Inject constructor(
    private val accountDao: AccountDao,
    private val sessionMappingDao: SessionMappingDao,
    private val qwenClient: QwenClient,
    private val logger: AppLogger
) {
    private var serverEngine: ApplicationEngine? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _serverPort = MutableStateFlow(3000)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val availableModels = listOf(
        "qwen3.7-plus", "qwen3.7-max", "qwen3.6-plus", "qwen3.6-plus-preview",
        "qwen3.5-plus", "qwen3.5-flash", "qwen3-coder-plus", "qwen3.6-max-preview",
        "qwen3.5-max-2026-03-08", "qwen3-vl-plus", "qwen3.5-omni-plus",
        "qwen3-omni-flash-2025-12-01", "qwen-plus-2025-07-28"
    ).flatMap { model ->
        listOf(model, "$model-no-thinking")
    }

    fun start(port: Int = 3000) {
        if (_isServerRunning.value) return
        _serverPort.value = port

        serverScope.launch {
            try {
                logger.info("Iniciando Ktor Server na porta $port...")
                serverEngine = embeddedServer(CIO, port = port) {
                    install(ContentNegotiation) {
                        json(json)
                    }
                    install(CORS) {
                        anyHost()
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Authorization)
                    }
                    routing {
                        get("/health") {
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        }
                        get("/v1/models") {
                            val models = availableModels.map { OpenAiModel(id = it) }
                            call.respond(HttpStatusCode.OK, OpenAiModelList(data = models))
                        }
                        post("/v1/chat/completions") {
                            handleChatCompletions(call)
                        }
                    }
                }.start(wait = false)
                _isServerRunning.value = true
                logger.info("Servidor ativo e aguardando requisições em http://localhost:$port")
            } catch (e: Exception) {
                logger.error("Erro ao iniciar o servidor: ${e.message}")
                _isServerRunning.value = false
            }
        }
    }

    fun stop() {
        if (!_isServerRunning.value) return
        serverScope.launch {
            try {
                logger.info("Parando Ktor Server...")
                serverEngine?.stop(1000, 2000)
                serverEngine = null
                _isServerRunning.value = false
                logger.info("Servidor parado com sucesso.")
            } catch (e: Exception) {
                logger.error("Erro ao parar o servidor: ${e.message}")
            }
        }
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        val request = try {
            call.receive<OpenAiRequest>()
        } catch (e: Exception) {
            logger.error("Falha ao analisar o corpo da requisição: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Corpo JSON malformado"))
            return
        }

        // 1. Verificar contas ativas no banco
        val accounts = accountDao.getAllAccounts()
        val activeAccount = accounts.firstOrNull { it.isActive }
        if (activeAccount == null) {
            logger.warn("Nenhuma conta Qwen ativa encontrada.")
            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "Nenhuma conta Qwen conectada e ativa no aplicativo.")
            )
            return
        }

        logger.info("Recebida chamada v1/chat/completions para modelo: ${request.model}")

        // 2. Resolver chaves de sessão para persistência no Qwen
        val conversationKey = request.session_id ?: request.conversation_id ?: deriveSessionId(request.messages)
        val mapping = sessionMappingDao.getMapping(conversationKey)

        var chatId: String? = mapping?.chatSessionId
        var parentId: String? = mapping?.parentMessageId

        val formatted = formatOpenAiMessages(request.messages)

        // Se for a primeira mensagem e não temos sessão, cria uma no Qwen
        if (chatId.isNullOrBlank()) {
            chatId = qwenClient.createChatSession(
                cookie = activeAccount.cookies,
                userAgent = activeAccount.userAgent,
                model = request.model
            )
            if (chatId.isNullOrBlank()) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Falha ao criar sessão de conversa no Qwen."))
                return
            }
            parentId = null
            sessionMappingDao.insertMapping(
                SessionMappingEntity(
                    conversationKey = conversationKey,
                    chatSessionId = chatId,
                    parentMessageId = null
                )
            )
        }

        // Decidir se o prompt será a história completa (caso de nova sessão lógica) ou incremental
        val promptText = if (mapping == null) formatted.fullPrompt else formatted.currentPrompt
        val enableThinking = !request.model.endsWith("-no-thinking")

        val completionId = "chatcmpl-" + UUID.randomUUID().toString()
        val createdTime = System.currentTimeMillis() / 1000

        if (request.stream) {
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                // Enviar chunk inicial com a role
                val initialChunk = OpenAiChunk(
                    id = completionId,
                    created = createdTime,
                    model = request.model,
                    choices = listOf(
                        OpenAiChoice(
                            index = 0,
                            delta = OpenAiDelta(role = "assistant")
                        )
                    )
                )
                writeStringUtf8("data: ${json.encodeToString(initialChunk)}\n\n")

                var finalParentId = parentId

                qwenClient.sendChatCompletion(
                    cookie = activeAccount.cookies,
                    userAgent = activeAccount.userAgent,
                    model = request.model,
                    chatId = chatId,
                    parentId = parentId,
                    prompt = promptText,
                    enableThinking = enableThinking
                ).collect { event ->
                    when (event) {
                        is QwenStreamEvent.Content -> {
                            val chunk = OpenAiChunk(
                                id = completionId,
                                created = createdTime,
                                model = request.model,
                                choices = listOf(
                                    OpenAiChoice(
                                        index = 0,
                                        delta = OpenAiDelta(content = event.text)
                                    )
                                )
                            )
                            writeStringUtf8("data: ${json.encodeToString(chunk)}\n\n")
                        }
                        is QwenStreamEvent.Thinking -> {
                            val chunk = OpenAiChunk(
                                id = completionId,
                                created = createdTime,
                                model = request.model,
                                choices = listOf(
                                    OpenAiChoice(
                                        index = 0,
                                        delta = OpenAiDelta(reasoning_content = event.text)
                                    )
                                )
                            )
                            writeStringUtf8("data: ${json.encodeToString(chunk)}\n\n")
                        }
                        is QwenStreamEvent.SessionUpdated -> {
                            finalParentId = event.messageId
                            sessionMappingDao.insertMapping(
                                SessionMappingEntity(
                                    conversationKey = conversationKey,
                                    chatSessionId = event.chatId,
                                    parentMessageId = event.messageId
                                )
                            )
                        }
                        is QwenStreamEvent.Error -> {
                            writeStringUtf8("data: {\"error\": \"${event.message}\"}\n\n")
                        }
                        is QwenStreamEvent.Done -> {
                            val doneChunk = OpenAiChunk(
                                id = completionId,
                                created = createdTime,
                                model = request.model,
                                choices = listOf(
                                    OpenAiChoice(
                                        index = 0,
                                        delta = OpenAiDelta(),
                                        finish_reason = "stop"
                                    )
                                )
                            )
                            writeStringUtf8("data: ${json.encodeToString(doneChunk)}\n\n")
                            writeStringUtf8("data: [DONE]\n\n")
                        }
                    }
                    flush()
                }
            }
        } else {
            // Caso não seja streaming, acumulamos as respostas em memória
            var accumulatedContent = ""
            var accumulatedThinking = ""
            var finalParentId = parentId

            var isError = false
            var errorMessage = ""

            qwenClient.sendChatCompletion(
                cookie = activeAccount.cookies,
                userAgent = activeAccount.userAgent,
                model = request.model,
                chatId = chatId,
                parentId = parentId,
                prompt = promptText,
                enableThinking = enableThinking
            ).collect { event ->
                when (event) {
                    is QwenStreamEvent.Content -> accumulatedContent += event.text
                    is QwenStreamEvent.Thinking -> accumulatedThinking += event.text
                    is QwenStreamEvent.SessionUpdated -> {
                        finalParentId = event.messageId
                        sessionMappingDao.insertMapping(
                            SessionMappingEntity(
                                conversationKey = conversationKey,
                                chatSessionId = event.chatId,
                                parentMessageId = event.messageId
                            )
                        )
                    }
                    is QwenStreamEvent.Error -> {
                        isError = true
                        errorMessage = event.message
                    }
                    else -> {}
                }
            }

            if (isError) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to errorMessage))
            } else {
                val responseMessage = OpenAiMessage(
                    role = "assistant",
                    content = accumulatedContent,
                    reasoning_content = if (accumulatedThinking.isNotEmpty()) accumulatedThinking else null
                )
                val totalLength = promptText.length + accumulatedContent.length
                val usage = OpenAiUsage(
                    prompt_tokens = promptText.length / 4,
                    completion_tokens = accumulatedContent.length / 4,
                    total_tokens = totalLength / 4
                )
                val result = OpenAiCompletionResponse(
                    id = completionId,
                    created = createdTime,
                    model = request.model,
                    choices = listOf(
                        OpenAiCompletionChoice(
                            index = 0,
                            message = responseMessage
                        )
                    ),
                    usage = usage
                )
                call.respond(HttpStatusCode.OK, result)
            }
        }
    }

    private fun formatOpenAiMessages(messages: List<OpenAiMessage>): FormattedPrompt {
        val systemPrompt = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content ?: "" }
        val activeMessages = messages.filter { it.role != "system" }

        val promptParts = mutableListOf<String>()
        val currentPromptParts = mutableListOf<String>()

        for (i in activeMessages.indices) {
            val msg = activeMessages[i]
            val content = msg.content ?: ""
            val segment = when (msg.role) {
                "user" -> "User: $content\n\n"
                "assistant" -> "Assistant: ${msg.reasoning_content?.let { "$it\n" } ?: ""}$content\n\n"
                "tool", "function" -> "Tool [${msg.name ?: "unknown"}]: $content\n\n"
                else -> "$content\n\n"
            }
            promptParts.add(segment)
            if (i == activeMessages.lastIndex) {
                currentPromptParts.add(segment)
            }
        }

        return FormattedPrompt(
            systemPrompt = systemPrompt,
            fullPrompt = promptParts.joinToString(""),
            currentPrompt = currentPromptParts.joinToString("")
        )
    }

    private fun deriveSessionId(messages: List<OpenAiMessage>): String {
        if (messages.size <= 1) return UUID.randomUUID().toString()
        val historyText = messages.dropLast(1).joinToString("|") { "${it.role}:${it.content}" }
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(historyText.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
    }
}
