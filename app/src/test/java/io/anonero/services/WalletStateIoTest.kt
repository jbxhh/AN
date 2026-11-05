package io.anonero.services

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Test

class WalletStateIoTest {
    @Test
    fun executesBlockingWalletOperationsOffTheCallingThread() = runBlocking {
        val callingThread = Thread.currentThread()
        var operationThread: Thread? = null

        withWalletStateIo {
            operationThread = Thread.currentThread()
        }

        assertNotSame(callingThread, operationThread)
    }
}
