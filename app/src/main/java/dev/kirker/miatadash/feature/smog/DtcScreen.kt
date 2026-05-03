package dev.kirker.miatadash.feature.smog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.obd.Dtc
import dev.kirker.miatadash.core.obd.DtcDecoder
import dev.kirker.miatadash.core.obd.ObdSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DtcViewModel @Inject constructor(
    private val session: ObdSession,
) : ViewModel() {
    data class Codes(val active: List<Dtc>, val pending: List<Dtc>, val permanent: List<Dtc>)

    private val _codes = MutableStateFlow(Codes(emptyList(), emptyList(), emptyList()))
    val codes: StateFlow<Codes> = _codes.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val active = (session.sendAndAwait("03") ?: emptyList()).let(DtcDecoder::decode)
            val pending = (session.sendAndAwait("07") ?: emptyList()).let(DtcDecoder::decode)
            val permanent = (session.sendAndAwait("0A") ?: emptyList()).let(DtcDecoder::decode)
            _codes.value = Codes(active, pending, permanent)
        }
    }
}

@Composable
fun DtcScreen(vm: DtcViewModel = hiltViewModel()) {
    val codes by vm.codes.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Diagnostic Trouble Codes", style = MaterialTheme.typography.titleLarge)
            Text("Read-only. This app does not clear codes — use a paid OBD app or scan tool when you need to reset.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.padding(vertical = 8.dp)) {
                Button(onClick = vm::refresh) { Text("Read codes") }
            }
        }

        section(this, "Active (Mode 03)", codes.active)
        section(this, "Pending (Mode 07)", codes.pending)
        section(this, "Permanent (Mode 0A)", codes.permanent)
    }
}

private fun section(scope: androidx.compose.foundation.lazy.LazyListScope, title: String, list: List<Dtc>) {
    scope.item {
        Text(title, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        if (list.isEmpty()) {
            Text("— none —", style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (list.isNotEmpty()) {
        scope.items(list) { dtc ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(dtc.code, style = MaterialTheme.typography.headlineMedium)
                Text(DtcDecoder.describe(dtc.code), style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
        }
    }
}
