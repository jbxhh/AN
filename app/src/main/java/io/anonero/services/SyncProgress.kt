package io.anonero.services

/**
 * Computes progress only from real chain heights. A zero/unknown daemon height
 * is not evidence that synchronization has completed.
 */
internal fun calculateSyncProgress(
    chainHeight: Long,
    daemonHeight: Long,
    targetHeight: Long
): SyncProgress? {
    if (chainHeight < 0L || daemonHeight <= 0L) return null

    val endHeight = maxOf(daemonHeight, targetHeight.coerceAtLeast(0L))
    if (endHeight <= 0L) return null

    val remaining = (endHeight - chainHeight).coerceAtLeast(0L)
    val progress = (chainHeight.toDouble() / endHeight.toDouble())
        .coerceIn(0.0, 1.0)
        .toFloat()
    return SyncProgress(progress, remaining)
}
