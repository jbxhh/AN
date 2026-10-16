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

import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import kotlinx.serialization.Serializable

@Serializable
class TransactionInfo : Parcelable, Comparable<TransactionInfo> {
    var direction: Direction
    var isPending: Boolean
    var isFailed: Boolean
    var amount: Long
    var fee: Long
    var blockheight: Long
    var hash: String?
    var timestamp: Long
    var paymentId: String?
    var accountIndex: Int
    var addressIndex: Int
    var confirmations: Long
    var subaddressLabel: String?
    var transfers: List<Transfer>? = listOf()
    var notes: String? = null

    constructor(
        direction: Int,
        isPending: Boolean,
        isFailed: Boolean,
        amount: Long,
        fee: Long,
        blockheight: Long,
        hash: String?,
        timestamp: Long,
        paymentId: String?,
        accountIndex: Int,
        addressIndex: Int,
        confirmations: Long,
        subaddressLabel: String?,
        transfers: List<Transfer>?
    ) {
        this.direction = Direction.entries.toTypedArray()[direction]
        this.isPending = isPending
        this.isFailed = isFailed
        this.amount = amount
        this.fee = fee
        this.blockheight = blockheight
        this.hash = hash
        this.timestamp = timestamp
        this.paymentId = paymentId
        this.accountIndex = accountIndex
        this.addressIndex = addressIndex
        this.confirmations = confirmations
        this.subaddressLabel = subaddressLabel
        this.transfers = transfers
    }

    private constructor(`in`: Parcel) {
        direction = Direction.fromInteger(`in`.readInt())
        isPending = `in`.readByte().toInt() != 0
        isFailed = `in`.readByte().toInt() != 0
        amount = `in`.readLong()
        fee = `in`.readLong()
        blockheight = `in`.readLong()
        hash = `in`.readString()
        timestamp = `in`.readLong()
        paymentId = `in`.readString()
        accountIndex = `in`.readInt()
        addressIndex = `in`.readInt()
        confirmations = `in`.readLong()
        subaddressLabel = `in`.readString()
        transfers = transfers?.toMutableList()?.also { transfersList ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                `in`.readList(transfersList, Transfer::class.java.classLoader, Transfer::class.java)
            } else {
                @Suppress("DEPRECATION")
                `in`.readList(transfersList, Transfer::class.java.classLoader)
            }
        }
        notes = `in`.readString()
    }

    val isConfirmed: Boolean
        get() = confirmations >= CONFIRMATION
    val displayLabel: String?
        get() = if (subaddressLabel?.isEmpty() == true || Subaddress.DEFAULT_LABEL_FORMATTER.matcher(
                subaddressLabel.toString()
            ).matches()
        ) "#$addressIndex" else subaddressLabel

    override fun toString(): String {
        return "$direction@$blockheight $amount"
    }

    fun getListKey(): String {
        return this.hash ?: "${timestamp}:${direction.value}:${amount}"
    }

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeInt(direction.value)
        out.writeByte((if (isPending) 1 else 0).toByte())
        out.writeByte((if (isFailed) 1 else 0).toByte())
        out.writeLong(amount)
        out.writeLong(fee)
        out.writeLong(blockheight)
        out.writeString(hash)
        out.writeLong(timestamp)
        out.writeString(paymentId)
        out.writeInt(accountIndex)
        out.writeInt(addressIndex)
        out.writeLong(confirmations)
        out.writeString(subaddressLabel)
        transfers?.let {
            out.writeList(transfers)
        }
        out.writeString(notes)
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun compareTo(other: TransactionInfo): Int {
        val b1 = timestamp
        val b2 = other.timestamp
        return if (b1 > b2) {
            -1
        } else if (b1 < b2) {
            1
        } else {
            hash?.let { other.hash?.compareTo(it) } ?: 0
        }
    }

    enum class Direction(val value: Int) {
        Direction_In(0), Direction_Out(1);

        companion object {
            fun fromInteger(n: Int): Direction {
                return when (n) {
                    0 -> Direction_In
                    else -> Direction_Out
                }
            }
        }
    }

    companion object {
        const val CONFIRMATION = 10 // blocks

        @JvmField
        val CREATOR: Creator<TransactionInfo> = object : Creator<TransactionInfo> {
            override fun createFromParcel(`in`: Parcel): TransactionInfo {
                return TransactionInfo(`in`)
            }

            override fun newArray(size: Int): Array<TransactionInfo?> {
                return arrayOfNulls(size)
            }
        }
    }
}