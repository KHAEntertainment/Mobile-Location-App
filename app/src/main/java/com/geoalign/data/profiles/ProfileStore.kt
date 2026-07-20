package com.geoalign.data.profiles

import com.geoalign.core.model.LocationProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistence boundary for location profiles (spec §9). Kept as an interface so the storage
 * backend (JSON file, and later possibly a DB) is swappable and so tests use an in-memory fake.
 */
interface ProfileStore {
    suspend fun list(): List<LocationProfile>
    suspend fun get(id: String): LocationProfile?
    /** Insert or replace by id. */
    suspend fun upsert(profile: LocationProfile)
    suspend fun delete(id: String)
    /** Remove all stored profiles (spec §25 "Clear profiles"). */
    suspend fun clear()
}

/** Simple thread-safe in-memory store — used in tests and as a fallback. */
class InMemoryProfileStore(initial: List<LocationProfile> = emptyList()) : ProfileStore {
    private val mutex = Mutex()
    private val byId = LinkedHashMap<String, LocationProfile>().apply {
        initial.forEach { put(it.id, it) }
    }

    override suspend fun list(): List<LocationProfile> = mutex.withLock { byId.values.toList() }
    override suspend fun get(id: String): LocationProfile? = mutex.withLock { byId[id] }
    override suspend fun upsert(profile: LocationProfile) = mutex.withLock { byId[profile.id] = profile }
    override suspend fun delete(id: String) { mutex.withLock { byId.remove(id) } }
    override suspend fun clear() = mutex.withLock { byId.clear() }
}
