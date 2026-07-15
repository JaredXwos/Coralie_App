package com.jaredxwos.coralie.storage

import com.jaredxwos.coralie.storage.database.AppDao
import com.jaredxwos.coralie.storage.database.DataEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicInteger

class HtmlStorage(
    private val spaceId: Long,
    private val dao: AppDao
)
{
    // Own scope so close() can cancel exactly this instance's in-flight
    // work — never the caller's coroutine — if the safety-net timeout fires.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicInteger(0)

    private suspend fun <T> exceptionLogger(block: suspend () -> T): Result<T> {
        inFlight.incrementAndGet()
        return try {
            Result.success(scope.async { block() }.await())
        } catch (e: CancellationException) {
            throw e // never swallow cancellation — rethrow, don't wrap as failure
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            inFlight.decrementAndGet()
        }
    }
    suspend fun createValue(name: String, value: String, tag: String?): Result<Unit> = exceptionLogger {
        dao.createValue(spaceId, name, value, tag)
    }
    suspend fun retrieveValue(name: String): Result<String> = exceptionLogger {
        dao.retrieveValue(spaceId, name)
            ?: throw NoSuchElementException("No entry named '$name' in this scope")
    }

    suspend fun updateValue(name: String, value: String, upsert: Boolean = true): Result<Unit> = exceptionLogger {
        dao.updateValue(spaceId, name, value, upsert)
    }

    suspend fun deleteItem(name: String): Result<Unit> = exceptionLogger {
        // Fails silently if no name matches
        dao.deleteData(spaceId, name)
    }

    suspend fun getTag(name: String): Result<String?> = exceptionLogger {
        if (!dao.nameExists(spaceId, name)) {
            throw NoSuchElementException("No entry named '$name' in this scope")
        }
        dao.retrieveTag(spaceId, name)
    }

    suspend fun setTag(name: String, tag: String?): Result<Unit> = exceptionLogger {
        if (!dao.nameExists(spaceId, name)) {
            throw NoSuchElementException("No entry named '$name' in this scope")
        }
        dao.updateTag(spaceId, name, tag)
    }

    suspend fun getAllWithTag(tag: String): Result<List<DataEntry>> = exceptionLogger {
        dao.retrieveAllWithTag(spaceId, tag)
    }

    suspend fun clear(): Result<Unit> = exceptionLogger {
        dao.clearSpace(spaceId)
    }

    suspend fun close(timeout: Duration = 5.seconds) {
        // Set to timeout allowing hanging html to close
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds

        while (inFlight.get() > 0) {
            if (System.currentTimeMillis() >= deadline) {
                scope.coroutineContext.cancelChildren()
                break
            }
            delay(10.milliseconds)
        }
    }
}