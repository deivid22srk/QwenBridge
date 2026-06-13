package com.deivid22srk.qwenbridge.client

import com.deivid22srk.qwenbridge.utils.AppLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class QwenChatRequest(
    val title: String,
    val models: List<String>,
    val chat_mode: String = "normal",
    val chat_type: String = "t2t",
    val timestamp: Long,
    val project_id: String = ""
)

@Serializable
data class QwenChatResponse(
    val chat_id: String? = null,
    val id: String? = null,
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class QwenPayload(
    val stream: Boolean = true,
    val version: String = "2.1",
    val incremental_output: Boolean = true,
    val chat_id: String? = null,
    val chat_mode: String = "normal",
    val model: String,
    val parent_id: String? = null,
    val messages: List<QwenMessage>,
    val timestamp: Long
)

@Serializable
data class QwenMessage(
    val fid: String,
    val parentId: String?,
    val childrenIds: List<String> = emptyList(),
    val role: String = "user",
    val content: String,
    val user_action: String = "chat",
    val files: List<String> = emptyList(),
    val timestamp: Long,
    val models: List<String>,
    val chat_type: String = "t2t",
    val feature_config: QwenFeatureConfig,
    val sub_chat_type: String = "t2t",
    val parent_id: String?
)

@Serializable
data class QwenFeatureConfig(
    val thinking_enabled: Boolean,
    val output_schema: String = "phase",
    val research_mode: String = "normal",
    val auto_thinking: Boolean = false,
    val thinking_mode: String = "Thinking",
    val thinking_format: String = "summary",
    val auto_search: Boolean = false
)

@Serializable
data class QwenChunk(
    val response_id: String? = null,
    val choices: List<QwenChoice>? = null,
    val error: QwenChunkError? = null
)

@Serializable
data class QwenChoice(
    val index: Int,
    val delta: QwenDelta? = null,
    val finish_reason: String? = null
)

@Serializable
data class QwenDelta(
    val phase: String? = null, // "thinking_summary" or "answer"
    val content: String? = null,
    val extra: QwenDeltaExtra? = null
)

@Serializable
data class QwenDeltaExtra(
    val summary_title: QwenExtraContent? = null,
    val summary_thought: QwenExtraContent? = null
)

@Serializable
data class QwenExtraContent(
    val content: List<String>? = null
)

@Serializable
data class QwenChunkError(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null
)

sealed class QwenStreamEvent {
    data class Content(val text: String) : QwenStreamEvent()
    data class Thinking(val text: String) : QwenStreamEvent()
    data class SessionUpdated(val chatId: String, val messageId: String) : QwenStreamEvent()
    data class Error(val message: String) : QwenStreamEvent()
    object Done : QwenStreamEvent()
}

@Singleton
class QwenClient @Inject constructor(
    private val logger: AppLogger
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300000
            connectTimeoutMillis = 15000
            socketTimeoutMillis = 300000
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }

    private val defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    suspend fun createChatSession(cookie: String, userAgent: String?, model: String): String? {
        val ua = if (userAgent.isNullOrBlank()) defaultUserAgent else userAgent
        val url = "https://chat.qwen.ai/api/v2/chats/new"
        logger.info("Tentando criar nova sessão de chat no Qwen...")

        try {
            val response: HttpResponse = httpClient.post(url) {
                headers {
                    append(HttpHeaders.ContentType, "application/json")
                    append(HttpHeaders.Cookie, cookie)
                    append("User-Agent", ua)
                    append("Origin", "https://chat.qwen.ai")
                    append("Referer", "https://chat.qwen.ai/c/new-chat")
                    append("source", "web")
                    append("version", "0.2.63")
                }
                setBody(
                    QwenChatRequest(
                        title = "Nova Conversa",
                        models = listOf(model.replace("-no-thinking", "")),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.error("Falha ao criar chat no Qwen. Status: ${response.status}. Corpo: $errorBody")
                return null
            }

            val body = response.body<QwenChatResponse>()
            val chatId = body.chat_id ?: body.id
            if (chatId != null) {
                logger.info("Nova sessão de chat criada com sucesso. Chat ID: $chatId")
                return chatId
            }

            logger.error("Resposta de criação de chat bem-sucedida, mas chat_id é nulo.")
            return null
        } catch (e: Exception) {
            logger.error("Exceção ao criar sessão de chat: ${e.message}")
            return null
        }
    }

    fun sendChatCompletion(
        cookie: String,
        userAgent: String?,
        model: String,
        chatId: String?,
        parentId: String?,
        prompt: String,
        enableThinking: Boolean
    ): Flow<QwenStreamEvent> = flow {
        val ua = if (userAgent.isNullOrBlank()) defaultUserAgent else userAgent
        val modelClean = model.replace("-no-thinking", "")

        val url = if (!chatId.isNullOrBlank()) {
            "https://chat.qwen.ai/api/v2/chat/completions?chat_id=$chatId"
        } else {
            "https://chat.qwen.ai/api/v2/chat/completions"
        }

        val timestamp = System.currentTimeMillis() / 1000
        val fid = UUID.randomUUID().toString()

        val qwenMsg = QwenMessage(
            fid = fid,
            parentId = parentId,
            childrenIds = emptyList(),
            role = "user",
            content = prompt,
            user_action = "chat",
            timestamp = timestamp,
            models = listOf(modelClean),
            chat_type = "t2t",
            feature_config = QwenFeatureConfig(
                thinking_enabled = enableThinking,
                output_schema = "phase",
                auto_thinking = false,
                thinking_mode = "Thinking",
                thinking_format = "summary",
                auto_search = false
            ),
            parent_id = parentId
        )

        val payload = QwenPayload(
            stream = true,
            version = "2.1",
            incremental_output = true,
            chat_id = chatId,
            model = modelClean,
            parent_id = parentId,
            messages = listOf(qwenMsg),
            timestamp = timestamp + 1
        )

        logger.info("Iniciando requisição de completamento no Qwen. Chat ID: $chatId, Parent ID: $parentId")

        try {
            httpClient.preparePost(url) {
                headers {
                    append(HttpHeaders.ContentType, "application/json")
                    append(HttpHeaders.Cookie, cookie)
                    append("User-Agent", ua)
                    append("Origin", "https://chat.qwen.ai")
                    append("Referer", if (!chatId.isNullOrBlank()) "https://chat.qwen.ai/c/$chatId" else "https://chat.qwen.ai/c/new-chat")
                    append("X-Request-Id", UUID.randomUUID().toString())
                    append("source", "web")
                    append("version", "0.2.63")
                    append("bx-v", "2.5.36")
                }
                setBody(payload)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errText = response.bodyAsText()
                    emit(QwenStreamEvent.Error("Erro HTTP do Qwen: ${response.status} - $errText"))
                    return@execute
                }

                val channel = response.bodyAsChannel()
                var lastThinkingSummary = ""
                var lastThinkingSummaryLength = 0
                var lastThinkingSummarySuffix = ""

                var lastRawContent = ""
                var lastRawContentLength = 0
                var lastRawContentSuffix = ""

                var activeResponseId: String? = null
                var emittedSessionUpdate = false

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue

                    val dataStr = trimmed.substring(5).trim()
                    if (dataStr == "[DONE]") {
                        break
                    }

                    try {
                        val chunk = json.decodeFromString<QwenChunk>(dataStr)

                        if (chunk.error != null) {
                            val errDetails = chunk.error.details ?: chunk.error.message ?: "Erro desconhecido"
                            emit(QwenStreamEvent.Error("Erro no Stream Qwen: ${chunk.error.code} - $errDetails"))
                            break
                        }

                        val responseId = chunk.response_id
                        if (responseId != null && activeResponseId == null) {
                            activeResponseId = responseId
                        }

                        if (chatId != null && activeResponseId != null && !emittedSessionUpdate) {
                            emit(QwenStreamEvent.SessionUpdated(chatId, activeResponseId))
                            emittedSessionUpdate = true
                        }

                        val delta = chunk.choices?.firstOrNull()?.delta
                        if (delta != null) {
                            if (delta.phase == "thinking_summary") {
                                val formattedSummary = formatThinkingSummary(delta)
                                if (formattedSummary.isNotEmpty()) {
                                    val incremental = getIncrementalDelta(
                                        lastThinkingSummary,
                                        formattedSummary,
                                        lastThinkingSummaryLength,
                                        lastThinkingSummarySuffix
                                    )
                                    lastThinkingSummary = incremental.matchedContent
                                    lastThinkingSummaryLength = incremental.contentLength
                                    lastThinkingSummarySuffix = incremental.contentSuffix

                                    if (incremental.delta.isNotEmpty()) {
                                        emit(QwenStreamEvent.Thinking(incremental.delta))
                                    }
                                }
                            } else if (delta.phase == "answer") {
                                val newContent = delta.content ?: ""
                                if (newContent.isNotEmpty()) {
                                    val incremental = getIncrementalDelta(
                                        lastRawContent,
                                        newContent,
                                        lastRawContentLength,
                                        lastRawContentSuffix
                                    )
                                    lastRawContent = incremental.matchedContent
                                    lastRawContentLength = incremental.contentLength
                                    lastRawContentSuffix = incremental.contentSuffix

                                    if (incremental.delta.isNotEmpty()) {
                                        emit(QwenStreamEvent.Content(incremental.delta))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignorar erros pequenos de parsing
                    }
                }
                emit(QwenStreamEvent.Done)
            }
        } catch (e: Exception) {
            logger.error("Erro na conexão com o stream do Qwen: ${e.message}")
            emit(QwenStreamEvent.Error("Conexão falhou: ${e.message}"))
        }
    }

    private fun formatThinkingSummary(delta: QwenDelta): String {
        val titles = delta.extra?.summary_title?.content ?: emptyList()
        val thoughts = delta.extra?.summary_thought?.content ?: emptyList()
        val sectionCount = maxOf(titles.size, thoughts.size)
        val sections = mutableListOf<String>()

        for (i in 0 until sectionCount) {
            val title = titles.getOrNull(i)?.trim() ?: ""
            val thought = thoughts.getOrNull(i)?.trim() ?: ""

            if (title.isNotEmpty() && thought.isNotEmpty()) {
                sections.add("**$title**\n\n$thought")
            } else if (title.isNotEmpty()) {
                sections.add("**$title**")
            } else if (thought.isNotEmpty()) {
                sections.add(thought)
            }
        }

        return sections.joinToString("\n\n")
    }

    // Algoritmo de diferença incremental
    data class DeltaResult(
        val delta: String,
        val matchedContent: String,
        val contentLength: Int,
        val contentSuffix: String
    )

    private fun buildDeltaResult(delta: String, matchedContent: String): DeltaResult {
        val suffix = if (matchedContent.length > 64) {
            matchedContent.substring(matchedContent.length - 64)
        } else {
            matchedContent
        }
        return DeltaResult(delta, matchedContent, matchedContent.length, suffix)
    }

    private fun getIncrementalDelta(
        oldStr: String,
        newStr: String,
        previousLength: Int,
        previousSuffix: String
    ): DeltaResult {
        if (oldStr.isEmpty()) {
            return buildDeltaResult(newStr, newStr)
        }
        if (newStr == oldStr) {
            return buildDeltaResult("", oldStr)
        }

        if (newStr.length > previousLength && previousLength > 0) {
            val checkLen = minOf(64, previousLength, previousSuffix.length)
            val expectedSuffix = previousSuffix.takeLast(checkLen)
            val actualSuffix = newStr.substring(previousLength - checkLen, previousLength)

            if (expectedSuffix == actualSuffix) {
                return buildDeltaResult(newStr.substring(previousLength), newStr)
            }
        }

        if (newStr.length >= oldStr.length && newStr.startsWith(oldStr)) {
            return buildDeltaResult(newStr.substring(oldStr.length), newStr)
        }

        val scanWindow = minOf(2000, oldStr.length)
        var commonPrefixLen = 0
        val maxLen = minOf(scanWindow, newStr.length)
        val segmentLen = 64

        while (commonPrefixLen + segmentLen <= maxLen) {
            val oldSeg = oldStr.substring(commonPrefixLen, commonPrefixLen + segmentLen)
            val newSeg = newStr.substring(commonPrefixLen, commonPrefixLen + segmentLen)
            if (oldSeg != newSeg) {
                break
            }
            commonPrefixLen += segmentLen
        }

        while (commonPrefixLen < maxLen && oldStr[commonPrefixLen] == newStr[commonPrefixLen]) {
            commonPrefixLen++
        }

        val threshold = minOf(scanWindow, 4)
        return if (commonPrefixLen >= threshold) {
            buildDeltaResult(newStr.substring(commonPrefixLen), newStr)
        } else {
            buildDeltaResult(newStr, oldStr + newStr)
        }
    }
}
