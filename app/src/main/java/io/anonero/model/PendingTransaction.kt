/*
 * Copyright (c) 2017 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.anonero.model

class PendingTransaction internal constructor(override var handle: Long) : StagingTransaction {
    val status: Status
        get() = Status.values()[getStatusJ()]

    external fun getStatusJ(): Int
    external fun getErrorString(): String?

    // commit transaction or save to file if filename is provided.
    external fun commit(filename: String?, overwrite: Boolean): Boolean

    external fun getAmount(): Long
    external fun getDust(): Long
    external fun getFee(): Long
    external fun getFirstTxIdJ(): String?
    external fun getTxCount(): Long

    enum class Status {
        Status_Ok, Status_Error, Status_Critical
    }

    enum class Priority(value: Int) {
        Priority_Default(0), Priority_Low(1), Priority_Medium(2), Priority_High(3), Priority_Last(4);

        companion object {
            fun fromInteger(n: Int): Priority? {
                when (n) {
                    0 -> return Priority_Default
                    1 -> return Priority_Low
                    2 -> return Priority_Medium
                    3 -> return Priority_High
                }
                return null
            }
        }
    }

    companion object {
        init {
            System.loadLibrary("anonero")
        }
    }
}