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

interface WalletListener {
    /**
     * moneySpent - called when money spent
     *
     * @param txId   - transaction id
     * @param amount - tvAmount
     */
    fun moneySpent(txId: String?, amount: Long)

    /**
     * moneyReceived - called when money received
     *
     * @param txId   - transaction id
     * @param amount - tvAmount
     */
    fun moneyReceived(txId: String?, amount: Long)

    /**
     * unconfirmedMoneyReceived - called when payment arrived in tx pool
     *
     * @param txId   - transaction id
     * @param amount - tvAmount
     */
    fun unconfirmedMoneyReceived(txId: String?, amount: Long)

    /**
     * newBlock      - called when a block is processed by the native wallet
     *
     * @param height - zero-based native block-height index (not the processed-block count)
     */
    fun newBlock(height: Long)

    /**
     * updated  - generic callback, called when any event (sent/received/block reveived/etc) happened with the wallet;
     */
    fun updated()

    /**
     * refreshed - called when wallet refreshed by background thread or explicitly refreshed by calling "refresh" synchronously
     */
    fun refreshed()
}