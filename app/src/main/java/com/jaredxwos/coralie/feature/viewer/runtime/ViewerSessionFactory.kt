package com.jaredxwos.coralie.feature.viewer.runtime

import com.jaredxwos.coralie.data.library.PageLibrary
import com.jaredxwos.coralie.data.library.model.PageDetails
import com.jaredxwos.coralie.data.permission.DomainPermissionStore
import com.jaredxwos.coralie.data.space.SpaceKeyValueStoreFactory
import kotlinx.coroutines.CoroutineScope

class ViewerSessionFactory(
    private val keyValueStoreFactory:
        SpaceKeyValueStoreFactory,
    private val domainPermissionStore:
        DomainPermissionStore,
    private val pageLibrary: PageLibrary,
) {
    fun create(
        page: PageDetails,
        parentScope: CoroutineScope,
    ): ViewerSession =
        ViewerSession(
            assetId = page.assetId,
            spaceId = page.spaceId,
            initialCapabilities =
                page.capabilities,
            keyValueStore =
                keyValueStoreFactory.create(
                    page.spaceId,
                ),
            domainPermissionStore =
                domainPermissionStore,
            pageLibrary = pageLibrary,
            parentScope = parentScope,
        )
}
