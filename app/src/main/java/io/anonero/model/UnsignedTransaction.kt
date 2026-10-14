package io.anonero.model

class UnsignedTransaction internal constructor(override var handle: Long) : StagingTransaction {
    enum class Status {
        Status_Ok,
        Status_Error,
        Status_Critical
    }

    enum class Priority(val value: Int) {
        Priority_Default(0),
        Priority_Low(1),
        Priority_Medium(2),
        Priority_High(3),
        Priority_Last(4);


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

    val status: Status
        get() = Status.entries[statusJ]

    val statusJ: Int
        external get

    val errorString: String?
        external get


    val address: String?
        external get

    val amount: Long
        // commit transaction or save to file if filename is provided.
        external get

    val fee: Long
        //    public native long getDust();
        external get

    val firstTxId: String
        get() {
            val id = firstTxIdJ
            return id
        }

    val firstTxIdJ: String
        external get //    public native long getTxCount();

    companion object {
        init {
            System.loadLibrary("anonero")
        }
    }
}
