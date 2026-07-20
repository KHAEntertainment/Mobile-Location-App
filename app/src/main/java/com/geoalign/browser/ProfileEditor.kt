package com.geoalign.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geoalign.core.model.LocationProfile
import com.geoalign.di.AppGraph
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Profile editor (spec §25). Edits the single active [LocationProfile] — or creates one if none
 * exists — and persists it. Coordinates are validated before Save is enabled. MVP keeps one
 * active profile, so saving replaces any existing one.
 */
@Composable
fun ProfileEditor(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AppGraph.profileStore(context) }
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var existingId by remember { mutableStateOf<String?>(null) }
    var createdAt by remember { mutableStateOf(0L) }

    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var accuracy by remember { mutableStateOf(LocationProfile.DEFAULT_ACCURACY_M.toString()) }
    var city by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var timezone by remember { mutableStateOf("UTC") }
    var locale by remember { mutableStateOf("en") }
    var languages by remember { mutableStateOf("en") }
    var desktop by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store.list().firstOrNull()?.let { p ->
            existingId = p.id
            createdAt = p.createdAtMillis
            name = p.name
            lat = p.latitude.toString()
            lng = p.longitude.toString()
            accuracy = p.accuracyMeters.toString()
            city = p.city.orEmpty()
            region = p.region.orEmpty()
            country = p.countryCode.orEmpty()
            timezone = p.timezone
            locale = p.primaryLocale
            languages = p.languages.joinToString(", ")
            desktop = p.desktopMode
        }
        loaded = true
    }

    val latD = lat.toDoubleOrNull()
    val lngD = lng.toDoubleOrNull()
    val valid = latD != null && latD in -90.0..90.0 &&
        lngD != null && lngD in -180.0..180.0 &&
        name.isNotBlank() && timezone.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Edit profile", style = MaterialTheme.typography.titleLarge)

        if (!loaded) {
            CircularProgressIndicator()
            return@Column
        }

        Field("Name", name) { name = it }
        Field("Latitude (-90..90)", lat, isError = latD == null || latD !in -90.0..90.0) { lat = it }
        Field("Longitude (-180..180)", lng, isError = lngD == null || lngD !in -180.0..180.0) { lng = it }
        Field("Accuracy (meters)", accuracy) { accuracy = it }
        Field("City", city) { city = it }
        Field("Region", region) { region = it }
        Field("Country code (ISO-2)", country) { country = it }
        Field("Timezone (IANA, e.g. Europe/London)", timezone, isError = timezone.isBlank()) { timezone = it }
        Field("Primary locale (e.g. en-GB)", locale) { locale = it }
        Field("Languages (comma-separated)", languages) { languages = it }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Desktop mode", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = desktop, onCheckedChange = { desktop = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = valid,
                onClick = {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val profile = LocationProfile(
                            id = existingId ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            countryCode = country.trim().ifBlank { null },
                            region = region.trim().ifBlank { null },
                            city = city.trim().ifBlank { null },
                            latitude = latD!!,
                            longitude = lngD!!,
                            accuracyMeters = accuracy.toDoubleOrNull() ?: LocationProfile.DEFAULT_ACCURACY_M,
                            timezone = timezone.trim(),
                            primaryLocale = locale.trim().ifBlank { "en" },
                            languages = languages.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                .ifEmpty { listOf("en") },
                            desktopMode = desktop,
                            createdAtMillis = if (existingId != null) createdAt else now,
                            updatedAtMillis = now,
                            generatedFromIp = false,
                        )
                        store.clear()
                        store.upsert(profile)
                        onDone()
                    }
                },
            ) { Text("Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }

        Text(
            "Changing location on an existing profile can conflict with cookies or account history " +
                "saved under it. Clear browser data if you switch regions.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Field(label: String, value: String, isError: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
