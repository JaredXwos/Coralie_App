package com.jaredxwos.coralie.storage

import android.net.Uri
import com.jaredxwos.coralie.storage.database.AppDao
import com.jaredxwos.coralie.storage.database.Space
import com.jaredxwos.coralie.utility.PersistentUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import androidx.core.net.toUri
import com.jaredxwos.coralie.storage.database.Html
import java.io.File

object AppStorage {
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: AppDao
    private lateinit var filesDir: File
    fun init(dao: AppDao, filesDir: File) {
        this.dao = dao
        this.filesDir = filesDir
    }

    var current: HtmlStorage? = null
        private set
    private var currentSpaceId: Long? = null
    fun internalPathFor(assetId: Long): File = File(filesDir, "html/$assetId.html")
    private suspend fun <T> exceptionLogger(block: suspend (AppDao) -> T): Result<T> {
        return try {
            if (!::dao.isInitialized) {
                throw IllegalStateException("AppStorage.init() was not called before use")
            }
            Result.success(block(dao))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun closeSpaceSync() = storageScope.launch { closeSpace() }
    suspend fun closeSpace(): Result<Unit> = exceptionLogger { dao ->
        current?.close()
        current = null
        currentSpaceId = null
    }

    suspend fun openSpace(spaceId: Long): Result<Unit> = exceptionLogger { dao ->
        if (current!=null) throw IllegalStateException("Cannot have multiple open spaces")
        currentSpaceId = spaceId
        current = HtmlStorage(spaceId, dao)
    }

    suspend fun createSpace(name: String): Result<Long> = exceptionLogger { dao ->
        dao.insertSpace(name)
    }

    suspend fun retrieveAllSpaces(): Result<List<Space>> = exceptionLogger { dao ->
        dao.retrieveAllSpaces()
    }

    suspend fun updateSpaceName(spaceId: Long, name: String): Result<Unit> = exceptionLogger { dao ->
        dao.updateSpaceName(spaceId, name)
    }

    suspend fun isSpaceLinked(spaceId: Long): Result<Boolean> = exceptionLogger { dao ->
        dao.isSpaceLinked(spaceId)
    }

    suspend fun deleteSpace(spaceId: Long): Result<Unit> = exceptionLogger { dao ->
        // If the space being deleted is the one currently active, close it
        // Delete space fails silently if space does not exist
        if (currentSpaceId == spaceId) closeSpace()
        dao.deleteSpace(spaceId)
    }

    suspend fun addHtml(spaceId: Long, name: String, sourceUri: Uri): Result<Long> = exceptionLogger {
        PersistentUri.persist(sourceUri).getOrThrow()
        dao.createHtml(spaceId, name, sourceUri.toString())
    }

    suspend fun retrieveAllHtml(): Result<List<Html>> = exceptionLogger { dao ->
        dao.retrieveAllHtml()
    }

    suspend fun cache(assetId: Long): Result<Unit> = exceptionLogger { dao ->
        PersistentUri.retrieveFrom(
            dao.retrieveUri(assetId).toUri(),
            internalPathFor(assetId)).getOrThrow()
    }

    suspend fun retrieveSourceUri(assetId: Long): Result<String> = exceptionLogger { dao ->
        dao.retrieveUri(assetId)
    }

    suspend fun removeHtml(assetId: Long): Result<Unit> = exceptionLogger { dao ->
        val sourceUri = dao.retrieveUri(assetId)
        dao.deleteHtml(assetId)
        if (!dao.uriExists(sourceUri))
            PersistentUri.release(sourceUri.toUri()).getOrThrow()
    }

    suspend fun retrieveAllDomains(): Result<List<String>> = exceptionLogger { dao ->
        dao.retrieveAllDomain()
    }

    suspend fun isDomainAllowed(domainUri: String): Result<Boolean> = exceptionLogger { dao ->
        dao.domainAllowed(domainUri)
    }

    suspend fun disallowDomain(domainUri: String): Result<Unit> = exceptionLogger { dao ->
        dao.disallowDomain(domainUri)
    }
}