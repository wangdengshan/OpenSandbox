/*
 * Copyright 2025 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.opensandbox.sandbox.infrastructure.adapters.service

import com.alibaba.opensandbox.sandbox.HttpClientProvider
import com.alibaba.opensandbox.sandbox.domain.exceptions.InvalidArgumentException
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunInSessionRequest
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint
import com.alibaba.opensandbox.sandbox.domain.services.BashSession
import com.alibaba.opensandbox.sandbox.infrastructure.adapters.converter.ExecutionEventDispatcher
import com.alibaba.opensandbox.sandbox.infrastructure.adapters.converter.jsonParser
import com.alibaba.opensandbox.sandbox.infrastructure.adapters.converter.parseSandboxError
import com.alibaba.opensandbox.sandbox.infrastructure.adapters.converter.toSandboxException
import com.alibaba.opensandbox.sandbox.api.models.execd.EventNode
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Headers.Companion.toHeaders
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError.Companion.UNEXPECTED_RESPONSE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

/**
 * Implementation of [BashSession] that calls execd session APIs via HTTP.
 */
internal class SessionAdapter(
    private val httpClientProvider: HttpClientProvider,
    private val execdEndpoint: SandboxEndpoint,
) : BashSession {
    companion object {
        private const val SESSION_PATH = "/session"
    }

    private val logger = LoggerFactory.getLogger(SessionAdapter::class.java)

    override fun createSession(cwd: String?): String {
        if (cwd != null && cwd.isBlank()) {
            throw InvalidArgumentException("cwd cannot be blank when provided")
        }
        try {
            val body =
                if (cwd != null) {
                    jsonParser.encodeToString(CreateSessionRequest(cwd = cwd))
                        .toRequestBody("application/json".toMediaType())
                } else {
                    null
                }
            val requestBuilder =
                Request.Builder()
                    .url("${httpClientProvider.config.protocol}://${execdEndpoint.endpoint}$SESSION_PATH")
                    .headers(execdEndpoint.headers.toHeaders())
            if (body != null) {
                requestBuilder.post(body)
            } else {
                requestBuilder.post(okhttp3.RequestBody.create(null, ByteArray(0)))
            }
            val request = requestBuilder.build()

            httpClientProvider.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBodyString = response.body?.string()
                    val sandboxError = parseSandboxError(errorBodyString)
                    throw SandboxApiException(
                        message = "Failed to create session. Status: ${response.code}, Body: $errorBodyString",
                        statusCode = response.code,
                        error = sandboxError ?: SandboxError(UNEXPECTED_RESPONSE),
                        requestId = response.header("X-Request-ID"),
                    )
                }
                val responseBody = response.body?.string() ?: throw SandboxApiException(
                    message = "create_session returned empty body",
                    statusCode = response.code,
                    error = SandboxError(UNEXPECTED_RESPONSE),
                    requestId = response.header("X-Request-ID"),
                )
                val parsed = jsonParser.decodeFromString<CreateSessionResponse>(responseBody)
                return parsed.sessionId
            }
        } catch (e: Exception) {
            logger.error("Failed to create session", e)
            throw e.toSandboxException()
        }
    }

    override fun runInSession(sessionId: String, request: RunInSessionRequest): Execution {
        if (sessionId.isBlank()) {
            throw InvalidArgumentException("session_id cannot be empty")
        }
        try {
            val apiRequest =
                RunInSessionRequestApi(
                    code = request.code,
                    cwd = request.cwd,
                    timeoutMs = request.timeoutMs,
                )
            val runUrl =
                "${httpClientProvider.config.protocol}://${execdEndpoint.endpoint}"
                    .toHttpUrlOrNull()!!
                    .newBuilder()
                    .addPathSegment("session")
                    .addPathSegment(sessionId)
                    .addPathSegment("run")
                    .build()
                    .toString()
            val httpRequest =
                Request.Builder()
                    .url(runUrl)
                    .post(
                        jsonParser.encodeToString(apiRequest).toRequestBody("application/json".toMediaType()),
                    )
                    .headers(execdEndpoint.headers.toHeaders())
                    .build()

            val execution = Execution()
            httpClientProvider.sseClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBodyString = response.body?.string()
                    val sandboxError = parseSandboxError(errorBodyString)
                    throw SandboxApiException(
                        message = "run_in_session failed. Status: ${response.code}, Body: $errorBodyString",
                        statusCode = response.code,
                        error = sandboxError ?: SandboxError(UNEXPECTED_RESPONSE),
                        requestId = response.header("X-Request-ID"),
                    )
                }
                response.body?.byteStream()?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val dispatcher = ExecutionEventDispatcher(execution, request.handlers)
                    reader.lineSequence()
                        .filter(String::isNotBlank)
                        .forEach { line ->
                            try {
                                val data = if (line.startsWith("data:")) line.drop(5).trim() else line
                                val eventNode = jsonParser.decodeFromString<EventNode>(data)
                                dispatcher.dispatch(eventNode)
                            } catch (e: Exception) {
                                logger.error("Failed to parse SSE line: {}", line, e)
                            }
                        }
                }
            }
            return execution
        } catch (e: Exception) {
            logger.error("Failed to run in session", e)
            throw e.toSandboxException()
        }
    }

    override fun deleteSession(sessionId: String) {
        if (sessionId.isBlank()) {
            throw InvalidArgumentException("session_id cannot be empty")
        }
        try {
            val deleteUrl =
                "${httpClientProvider.config.protocol}://${execdEndpoint.endpoint}"
                    .toHttpUrlOrNull()!!
                    .newBuilder()
                    .addPathSegment("session")
                    .addPathSegment(sessionId)
                    .build()
                    .toString()
            val request =
                Request.Builder()
                    .url(deleteUrl)
                    .delete()
                    .headers(execdEndpoint.headers.toHeaders())
                    .build()

            httpClientProvider.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBodyString = response.body?.string()
                    val sandboxError = parseSandboxError(errorBodyString)
                    throw SandboxApiException(
                        message = "delete_session failed. Status: ${response.code}, Body: $errorBodyString",
                        statusCode = response.code,
                        error = sandboxError ?: SandboxError(UNEXPECTED_RESPONSE),
                        requestId = response.header("X-Request-ID"),
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to delete session", e)
            throw e.toSandboxException()
        }
    }
}

@Serializable
private data class CreateSessionRequest(
    val cwd: String? = null,
)

@Serializable
private data class CreateSessionResponse(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
private data class RunInSessionRequestApi(
    val code: String,
    val cwd: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long? = null,
)
