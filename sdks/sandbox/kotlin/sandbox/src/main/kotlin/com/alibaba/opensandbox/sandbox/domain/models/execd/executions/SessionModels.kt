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

package com.alibaba.opensandbox.sandbox.domain.models.execd.executions

/**
 * Request to run code in an existing bash session.
 *
 * @property code Shell code to execute
 * @property cwd Optional working directory override for this run
 * @property timeoutMs Optional max execution time in milliseconds
 * @property handlers Optional execution handlers for streaming events
 */
class RunInSessionRequest private constructor(
    val code: String,
    val cwd: String?,
    val timeoutMs: Long?,
    val handlers: ExecutionHandlers?,
) {
    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var code: String? = null
        private var cwd: String? = null
        private var timeoutMs: Long? = null
        private var handlers: ExecutionHandlers? = null

        fun code(code: String): Builder {
            require(code.isNotBlank()) { "Code cannot be blank" }
            this.code = code
            return this
        }

        fun cwd(cwd: String?): Builder {
            this.cwd = cwd
            return this
        }

        fun timeoutMs(timeoutMs: Long?): Builder {
            this.timeoutMs = timeoutMs
            return this
        }

        fun handlers(handlers: ExecutionHandlers?): Builder {
            this.handlers = handlers
            return this
        }

        fun build(): RunInSessionRequest {
            val codeValue = code ?: throw IllegalArgumentException("Code must be specified")
            return RunInSessionRequest(
                code = codeValue,
                cwd = cwd,
                timeoutMs = timeoutMs,
                handlers = handlers,
            )
        }
    }
}
