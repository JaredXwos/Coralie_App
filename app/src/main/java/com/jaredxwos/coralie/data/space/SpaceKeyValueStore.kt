package com.jaredxwos.coralie.data.space

import com.jaredxwos.coralie.data.database.dao.PageStorageDao
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class SpaceKeyValueStore internal constructor(
    val spaceId: Long,
    private val dao: PageStorageDao,
) : AutoCloseable {
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO,
        )
    private val inFlight =
        AtomicInteger(0)
    private val closed =
        AtomicBoolean(false)

    suspend fun create(
        name: String,
        value: String,
        tag: String?,
    ): Result<Unit> =
        resultOf {
            dao.createValue(
                spaceId = spaceId,
                name = name,
                value = value,
                tag = tag,
            )
        }

    suspend fun get(
        name: String,
    ): Result<String> =
        resultOf {
            dao.retrieveValue(
                spaceId = spaceId,
                name = name,
            ) ?: throw NoSuchElementException(
                "No entry named '$name' in this scope",
            )
        }

    suspend fun set(
        name: String,
        value: String,
        upsert: Boolean = true,
    ): Result<Unit> =
        resultOf {
            dao.updateValue(
                spaceId = spaceId,
                name = name,
                value = value,
                upsert = upsert,
            )
        }

    suspend fun remove(
        name: String,
    ): Result<Unit> =
        resultOf {
            dao.deleteValue(
                spaceId = spaceId,
                name = name,
            )
        }

    suspend fun getTag(
        name: String,
    ): Result<String?> =
        resultOf {
            if (
                !dao.nameExists(
                    spaceId = spaceId,
                    name = name,
                )
            ) {
                throw NoSuchElementException(
                    "No entry named '$name' in this scope",
                )
            }

            dao.retrieveTag(
                spaceId = spaceId,
                name = name,
            )
        }

    suspend fun setTag(
        name: String,
        tag: String?,
    ): Result<Unit> =
        resultOf {
            if (
                !dao.nameExists(
                    spaceId = spaceId,
                    name = name,
                )
            ) {
                throw NoSuchElementException(
                    "No entry named '$name' in this scope",
                )
            }

            dao.updateTag(
                spaceId = spaceId,
                name = name,
                tag = tag,
            )
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            awaitInFlight()
            scope.cancel(
                "SpaceKeyValueStore closed",
            )
        }
    }

    private suspend fun awaitInFlight(
        timeout: Duration = 5.seconds,
    ) {
        val deadline =
            System.currentTimeMillis() +
                timeout.inWholeMilliseconds

        while (inFlight.get() > 0) {
            if (
                System.currentTimeMillis() >=
                deadline
            ) {
                return
            }
            delay(10L.milliseconds)
        }
    }

    private suspend fun <T> resultOf(
        block: suspend () -> T,
    ): Result<T> {
        check(!closed.get()) {
            "Space storage is closed"
        }

        inFlight.incrementAndGet()
        return try {
            Result.success(
                scope.async {
                    block()
                }.await(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
