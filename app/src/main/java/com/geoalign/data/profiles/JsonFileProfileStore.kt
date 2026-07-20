package com.geoalign.data.profiles

import com.geoalign.core.model.LocationProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed [ProfileStore] using kotlinx.serialization. Takes a plain [File] (not a Context), so
 * it is fully unit-testable on a temp file. Writes atomically (temp + rename) to avoid a corrupt
 * profile file on a crash mid-write (spec §26 "Profile corruption"). All IO on Dispatchers.IO.
 */
class JsonFileProfileStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
) : ProfileStore {

    private val serializer = ListSerializer(LocationProfile.serializer())
    private val mutex = Mutex()

    private suspend fun readAll(): MutableList<LocationProfile> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext mutableListOf()
        runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrElse { emptyList() } // treat unreadable/corrupt as empty rather than crashing
            .toMutableList()
    }

    private suspend fun writeAll(profiles: List<LocationProfile>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, profiles))
        if (!tmp.renameTo(file)) {
            // Fallback if atomic rename is unsupported on the filesystem.
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    override suspend fun list(): List<LocationProfile> = mutex.withLock { readAll() }

    override suspend fun get(id: String): LocationProfile? =
        mutex.withLock { readAll().firstOrNull { it.id == id } }

    override suspend fun upsert(profile: LocationProfile) = mutex.withLock {
        val all = readAll()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile else all.add(profile)
        writeAll(all)
    }

    override suspend fun delete(id: String) = mutex.withLock {
        val all = readAll()
        if (all.removeAll { it.id == id }) writeAll(all)
    }

    override suspend fun clear() = mutex.withLock { writeAll(emptyList()) }
}
