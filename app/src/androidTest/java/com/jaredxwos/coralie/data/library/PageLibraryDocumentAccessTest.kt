package com.jaredxwos.coralie.data.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaredxwos.coralie.data.database.CoralieDatabase
import com.jaredxwos.coralie.data.library.model.PageCapabilities
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageLibraryDocumentAccessTest {
    private lateinit var database: CoralieDatabase
    private lateinit var grants: FakeUriGrantStore
    private lateinit var cache: FakeHtmlPageCache
    private lateinit var library: PageLibrary

    @Before
    fun setUp() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(
                context,
                CoralieDatabase::class.java,
            ).build()
        grants = FakeUriGrantStore()
        cache = FakeHtmlPageCache(context.cacheDir)
        library = PageLibrary(database.libraryDao(), cache, grants)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistedGrantSurvivesLibraryRecreationAndReopen() = runBlocking {
        val source = Uri.parse("content://documents/page.html")
        val spaceId = database.libraryDao().insertSpace("Space")
        val assetId = library.importPage(
            spaceId,
            "Page",
            source,
            PageCapabilities.NONE,
        ).getOrThrow()

        val restarted =
            PageLibrary(database.libraryDao(), cache, grants)

        assertTrue(grants.hasPersistedReadAccess(source))
        assertTrue(restarted.refreshCached(assetId).isSuccess)
    }

    @Test
    fun revokedGrantPromptsAndReselectionRecoversWithoutChangingPage() =
        runBlocking {
            val oldSource = Uri.parse("content://documents/old.html")
            val newSource = Uri.parse("content://documents/new.html")
            val spaceId = database.libraryDao().insertSpace("Space")
            val assetId = library.importPage(
                spaceId,
                "Page",
                oldSource,
                PageCapabilities.NONE,
            ).getOrThrow()

            grants.revoke(oldSource)
            cache.inaccessible += oldSource

            val failure = library.refreshCached(assetId).exceptionOrNull()
            assertTrue(failure is DocumentAccessException)
            assertFalse((failure as DocumentAccessException).grantPresent)

            library.reselectSource(assetId, newSource).getOrThrow()
            val recovered = library.getPage(assetId).getOrThrow()

            assertEquals(assetId, recovered.assetId)
            assertEquals("Page", recovered.name)
            assertEquals(spaceId, recovered.spaceId)
            assertEquals(newSource, recovered.sourceUri)
            assertTrue(grants.hasPersistedReadAccess(newSource))
            assertFalse(grants.hasPersistedReadAccess(oldSource))
            assertTrue(library.refreshCached(assetId).isSuccess)
        }

    private class FakeUriGrantStore : UriGrantStore {
        private val granted = mutableSetOf<Uri>()

        override fun hasPersistedReadAccess(uri: Uri) = uri in granted

        override fun persist(uri: Uri): Result<Unit> {
            granted += uri
            return Result.success(Unit)
        }

        override fun release(uri: Uri): Result<Unit> {
            granted -= uri
            return Result.success(Unit)
        }

        fun revoke(uri: Uri) {
            granted -= uri
        }
    }

    @Test
    fun missingGrantCanBeReauthorizedWithTheSameDocumentUri() = runBlocking {
        val source = Uri.parse("content://documents/page.html")
        val spaceId = database.libraryDao().insertSpace("Space")
        val assetId = library.importPage(
            spaceId,
            "Page",
            source,
            PageCapabilities.NONE,
        ).getOrThrow()

        grants.revoke(source)
        cache.inaccessible += source
        assertTrue(library.refreshCached(assetId).exceptionOrNull() is DocumentAccessException)

        cache.inaccessible -= source
        library.reselectSource(assetId, source).getOrThrow()

        assertEquals(source, library.getPage(assetId).getOrThrow().sourceUri)
        assertTrue(grants.hasPersistedReadAccess(source))
        assertTrue(library.refreshCached(assetId).isSuccess)
    }

    @Test
    fun editRepersistsMissingGrantWhenSourceUriIsUnchanged() = runBlocking {
        val source = Uri.parse("content://documents/page.html")
        val firstSpace = database.libraryDao().insertSpace("First")
        val secondSpace = database.libraryDao().insertSpace("Second")
        val assetId = library.importPage(
            firstSpace,
            "Before",
            source,
            PageCapabilities.NONE,
        ).getOrThrow()
        grants.revoke(source)

        val replacementId = library.replacePage(
            assetId,
            secondSpace,
            "After",
            source,
            PageCapabilities.NONE,
        ).getOrThrow()

        assertEquals(assetId, replacementId)
        assertTrue(grants.hasPersistedReadAccess(source))
        assertEquals("After", library.getPage(assetId).getOrThrow().name)
    }

    @Test
    fun providerMissingWithRecordedGrantStillRequestsReselection() = runBlocking {
        val source = Uri.parse("content://documents/deleted.html")
        val spaceId = database.libraryDao().insertSpace("Space")
        val assetId = library.importPage(
            spaceId,
            "Page",
            source,
            PageCapabilities.NONE,
        ).getOrThrow()
        cache.inaccessible += source

        val failure =
            library.refreshCached(assetId).exceptionOrNull()
                as DocumentAccessException

        assertTrue(failure.grantPresent)
    }

    @Test
    fun failedReplacementKeepsStoredSourceAndReleasesUnusedNewGrant() =
        runBlocking {
            val oldSource = Uri.parse("content://documents/old.html")
            val badSource = Uri.parse("content://documents/missing.html")
            val spaceId = database.libraryDao().insertSpace("Space")
            val assetId = library.importPage(
                spaceId,
                "Page",
                oldSource,
                PageCapabilities.NONE,
            ).getOrThrow()
            cache.inaccessible += badSource

            assertTrue(library.reselectSource(assetId, badSource).isFailure)

            assertEquals(oldSource, library.getPage(assetId).getOrThrow().sourceUri)
            assertTrue(grants.hasPersistedReadAccess(oldSource))
            assertFalse(grants.hasPersistedReadAccess(badSource))
        }

    @Test
    fun failedReplacementDoesNotReleaseAGrantItDidNotAcquire() = runBlocking {
        val oldSource = Uri.parse("content://documents/old.html")
        val badSource = Uri.parse("content://documents/missing.html")
        val spaceId = database.libraryDao().insertSpace("Space")
        val assetId = library.importPage(
            spaceId,
            "Page",
            oldSource,
            PageCapabilities.NONE,
        ).getOrThrow()
        grants.persist(badSource).getOrThrow()
        cache.inaccessible += badSource

        assertTrue(library.reselectSource(assetId, badSource).isFailure)

        assertTrue(grants.hasPersistedReadAccess(badSource))
    }

    @Test
    fun failedImportDoesNotReleaseAnExistingGrant() = runBlocking {
        val source = Uri.parse("content://documents/page.html")
        grants.persist(source).getOrThrow()

        assertTrue(
            library.importPage(
                Long.MAX_VALUE,
                "Page",
                source,
                PageCapabilities.NONE,
            ).isFailure,
        )

        assertTrue(grants.hasPersistedReadAccess(source))
    }

    @Test
    fun privateCacheWriteFailureIsNotReportedAsRevokedAccess() = runBlocking {
        val source = Uri.parse("content://documents/page.html")
        val spaceId = database.libraryDao().insertSpace("Space")
        val assetId = library.importPage(
            spaceId,
            "Page",
            source,
            PageCapabilities.NONE,
        ).getOrThrow()
        cache.failure = java.io.IOException("cache disk full")

        val failure = library.refreshCached(assetId).exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertFalse(failure is DocumentAccessException)
    }

    private class FakeHtmlPageCache(
        private val directory: File,
    ) : HtmlPageCache {
        val inaccessible = mutableSetOf<Uri>()
        var failure: Exception? = null

        override suspend fun copyFromUri(
            assetId: Long,
            sourceUri: Uri,
        ): Result<File> =
            if (failure != null) {
                Result.failure(requireNotNull(failure))
            } else if (sourceUri in inaccessible) {
                Result.failure(
                    SourceDocumentReadException(
                        FileNotFoundException(sourceUri.toString()),
                    ),
                )
            } else {
                Result.success(File(directory, "$assetId.html"))
            }

        override fun delete(assetId: Long) = true
    }
}
