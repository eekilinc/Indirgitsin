package com.indirgitsin.app.data.downloader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Coordinates download concurrency and prioritizes waiting queue jobs. */
object DownloadQueueCoordinator {
    private const val MAX_CONCURRENT = 2
    private val mutex = Mutex()
    private val sequence = AtomicLong(1000L)
    private val priorityOverrides = mutableMapOf<UUID, Long>()

    private data class QueueEntry(
        val id: UUID,
        var priority: Long,
        val deferred: CompletableDeferred<Unit>
    )

    private val activeSlots = mutableSetOf<UUID>()
    private val waitingQueue = mutableListOf<QueueEntry>()

    private val _queueOrder = MutableStateFlow<Map<UUID, Int>>(emptyMap())
    val queueOrder: StateFlow<Map<UUID, Int>> = _queueOrder.asStateFlow()

    suspend fun <T> withPermit(workId: UUID, block: suspend () -> T): T {
        val deferred = CompletableDeferred<Unit>()
        val prio = mutex.withLock {
            priorityOverrides.remove(workId) ?: sequence.incrementAndGet()
        }

        mutex.withLock {
            if (activeSlots.size < MAX_CONCURRENT && waitingQueue.isEmpty()) {
                activeSlots.add(workId)
                deferred.complete(Unit)
            } else {
                waitingQueue.add(QueueEntry(workId, prio, deferred))
                updateRanksLocked()
            }
        }

        try {
            deferred.await()
            return block()
        } finally {
            mutex.withLock {
                activeSlots.remove(workId)
                waitingQueue.removeAll { it.id == workId }
                dispatchNextLocked()
                updateRanksLocked()
            }
        }
    }

    suspend fun prioritize(workId: UUID) {
        mutex.withLock {
            val minPrio = waitingQueue.minOfOrNull { it.priority } ?: 0L
            val newPrio = minPrio - 1
            priorityOverrides[workId] = newPrio

            val entry = waitingQueue.find { it.id == workId }
            if (entry != null) {
                entry.priority = newPrio
                waitingQueue.sortBy { it.priority }
                updateRanksLocked()
            } else if (!activeSlots.contains(workId)) {
                val currentOrder = _queueOrder.value.toMutableMap()
                currentOrder[workId] = 1
                _queueOrder.value = currentOrder
            }
        }
    }

    private fun dispatchNextLocked() {
        while (activeSlots.size < MAX_CONCURRENT && waitingQueue.isNotEmpty()) {
            waitingQueue.sortBy { it.priority }
            val next = waitingQueue.removeAt(0)
            activeSlots.add(next.id)
            next.deferred.complete(Unit)
        }
    }

    private fun updateRanksLocked() {
        waitingQueue.sortBy { it.priority }
        val map = waitingQueue.mapIndexed { index, entry -> entry.id to (index + 1) }.toMap()
        _queueOrder.value = map
    }

    suspend fun resetForTesting() {
        mutex.withLock {
            activeSlots.clear()
            waitingQueue.clear()
            priorityOverrides.clear()
            _queueOrder.value = emptyMap()
        }
    }
}
