package com.jaredxwos.coralie.data.permission

import com.jaredxwos.coralie.data.database.dao.PermissionDao
import kotlinx.coroutines.CancellationException

class DomainPermissionStore internal constructor(
    private val dao: PermissionDao,
) {
    suspend fun getAllowedDomains():
        Result<List<String>> =
        resultOf {
            dao.retrieveAllDomains()
        }

    suspend fun isAllowed(
        domain: String,
    ): Result<Boolean> =
        resultOf {
            dao.isDomainAllowed(
                normalize(domain),
            )
        }

    suspend fun allow(
        domain: String,
    ): Result<Unit> =
        resultOf {
            dao.allowDomain(
                normalize(domain),
            )
        }

    suspend fun revoke(
        domain: String,
    ): Result<Unit> =
        resultOf {
            dao.disallowDomain(
                normalize(domain),
            )
        }

    private fun normalize(domain: String): String =
        domain.trim()
            .trimEnd('.')
            .lowercase()

    private suspend fun <T> resultOf(
        block: suspend () -> T,
    ): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
}
