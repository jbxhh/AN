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

import timber.log.Timber

private const val TAG = "TransactionHistory"

class TransactionHistory(private val handle: Long, var accountIndex: Int) {
    var all: List<TransactionInfo> = ArrayList()
        private set

    fun setAccountFor(wallet: Wallet) {
        if (accountIndex != wallet.getAccountIndex()) {
            accountIndex = wallet.getAccountIndex()
            refreshWithNotes(wallet)
        }
    }

    private fun loadNotes(wallet: Wallet) {
        for (info in all) {
            info.notes = wallet.getUserNote(info.hash)
        }
    }

    external fun getCount(): Int

    fun refreshWithNotes(wallet: Wallet) {
        refresh()
        loadNotes(wallet)
    }

    private fun refresh() {
        val transactionInfos = refreshJ()
        Timber.tag(TAG).d("refresh size=%s", transactionInfos.size)
        val iterator = transactionInfos.iterator()
        while (iterator.hasNext()) {
            val info = iterator.next()
            if (info.accountIndex != accountIndex) {
                iterator.remove()
            }
        }
        all = transactionInfos
    }

    private external fun refreshJ(): MutableList<TransactionInfo>

    companion object {
        init {
            System.loadLibrary("anonero")
        }
    }
}