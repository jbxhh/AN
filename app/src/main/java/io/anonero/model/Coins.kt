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

class Coins(private val handle: Long){
    var all: List<CoinsInfo> = ArrayList()
        private set

    fun refresh() {
        val transactionInfos = refreshJ()
        all = transactionInfos
    }

    private external fun refreshJ(): List<CoinsInfo>
    private external fun setFrozenJ(publicKey: String)
    private external fun thawJ(publicKey: String)
    external fun getCount(): Int

    fun setFrozen(publicKey: String): Boolean {
        setFrozenJ(publicKey)
        refresh()
        return all.any { it.pub_key == publicKey && it.frozen }
    }

    fun thaw(publicKey: String): Boolean {
        thawJ(publicKey)
        refresh()
        return all.any { it.pub_key == publicKey && !it.frozen }
    }

    companion object {
        init {
            System.loadLibrary("anonero")
        }
    }
}
