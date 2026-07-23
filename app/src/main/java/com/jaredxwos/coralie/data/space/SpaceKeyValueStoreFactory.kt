package com.jaredxwos.coralie.data.space

import com.jaredxwos.coralie.data.database.dao.PageStorageDao

class SpaceKeyValueStoreFactory internal constructor(
    private val dao: PageStorageDao,
) {
    fun create(
        spaceId: Long,
    ): SpaceKeyValueStore =
        SpaceKeyValueStore(
            spaceId = spaceId,
            dao = dao,
        )
}
