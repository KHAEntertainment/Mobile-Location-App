package com.geoalign.data.profiles

import com.geoalign.core.model.LocationProfile
import com.geoalign.core.model.MeasurementSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileStoreTest {

    private fun sample(id: String, name: String = "London") = LocationProfile(
        id = id,
        name = name,
        countryCode = "GB",
        city = "London",
        latitude = 51.5074,
        longitude = -0.1278,
        timezone = "Europe/London",
        primaryLocale = "en-GB",
        languages = listOf("en-GB", "en"),
        measurementSystem = MeasurementSystem.METRIC,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
        generatedFromIp = true,
        sourceProvider = "ipwho.is",
    )

    @Test fun inMemoryCrud() = runBlocking {
        val store = InMemoryProfileStore()
        store.upsert(sample("a"))
        store.upsert(sample("b", "Berlin"))
        assertEquals(2, store.list().size)
        assertEquals("Berlin", store.get("b")!!.name)

        store.upsert(sample("b", "Munich"))
        assertEquals("Munich", store.get("b")!!.name)
        assertEquals(2, store.list().size)

        store.delete("a")
        assertNull(store.get("a"))
        assertEquals(1, store.list().size)

        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test fun jsonFileRoundTripsAcrossInstances() = runBlocking {
        val file = File.createTempFile("profiles", ".json").apply { delete() }
        try {
            val store1 = JsonFileProfileStore(file)
            store1.upsert(sample("a"))
            store1.upsert(sample("b", "Tokyo"))

            val store2 = JsonFileProfileStore(file)
            val loaded = store2.list().sortedBy { it.id }
            assertEquals(2, loaded.size)
            assertEquals("London", loaded[0].name)
            assertEquals(listOf("en-GB", "en"), loaded[0].languages)
            assertEquals("Tokyo", loaded[1].name)
        } finally {
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
        }
    }

    @Test fun corruptFileTreatedAsEmpty() = runBlocking {
        val file = File.createTempFile("profiles", ".json").apply { writeText("{ not valid json ][") }
        try {
            val store = JsonFileProfileStore(file)
            assertTrue(store.list().isEmpty())
            store.upsert(sample("a"))
            assertEquals(1, JsonFileProfileStore(file).list().size)
        } finally {
            file.delete()
        }
    }

    @Test fun deleteMissingIdIsNoop() = runBlocking {
        val file = File.createTempFile("profiles", ".json").apply { delete() }
        try {
            val store = JsonFileProfileStore(file)
            store.upsert(sample("a"))
            store.delete("does-not-exist")
            assertEquals(1, store.list().size)
        } finally {
            file.delete()
        }
    }
}
