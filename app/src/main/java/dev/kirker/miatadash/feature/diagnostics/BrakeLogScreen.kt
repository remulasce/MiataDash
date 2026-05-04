package dev.kirker.miatadash.feature.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.braking.BrakeEvent
import dev.kirker.miatadash.core.braking.BrakeEventLogger
import dev.kirker.miatadash.ui.components.BrakeEventDetail
import dev.kirker.miatadash.ui.components.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class BrakeLogViewModel @Inject constructor(
    private val logger: BrakeEventLogger,
) : ViewModel() {

    private val _events = MutableStateFlow<List<BrakeEvent>?>(null)   // null = loading
    val events: StateFlow<List<BrakeEvent>?> = _events.asStateFlow()

    init {
        reload()
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { logger.delete(id) }
            // Optimistic update: remove from list immediately; reload() would work too
            _events.value = _events.value?.filterNot { it.id == id }
        }
    }

    private fun reload() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { logger.loadAll() }
            _events.value = loaded
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun BrakeLogScreen(vm: BrakeLogViewModel = hiltViewModel()) {
    val events by vm.events.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title    = "Brake Log",
            subtitle = "Stored hard-braking events — most recent first",
        )

        when {
            events == null -> {
                // Loading from disk
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            events!!.isEmpty() -> {
                Spacer(Modifier.height(32.dp))
                Text(
                    "No events stored yet. Hard-braking events are saved automatically when detected.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            else -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                ) {
                    events!!.forEach { event ->
                        BrakeLogEventCard(
                            event    = event,
                            onDelete = { vm.delete(event.id) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// ── Per-event card ────────────────────────────────────────────────────────────

@Composable
private fun BrakeLogEventCard(event: BrakeEvent, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            // Header: timestamp + delete button
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        formatTimestamp(event.startMs),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "id ${event.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete event",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )

            // Reuse the same detail body used by the live dashboard card
            BrakeEventDetail(event = event)
        }
    }
}

private fun formatTimestamp(tsMs: Long): String =
    SimpleDateFormat("EEE MMM d, HH:mm:ss", Locale.getDefault()).format(Date(tsMs))
