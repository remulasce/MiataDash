package dev.kirker.miatadash.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.obd.ObdSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RawConsoleViewModel @Inject constructor(
    private val session: ObdSession,
) : ViewModel() {
    private val _events = MutableStateFlow<List<ObdSession.WireEvent>>(emptyList())
    val events: StateFlow<List<ObdSession.WireEvent>> = _events.asStateFlow()

    init {
        viewModelScope.launch {
            session.wire.collect { e ->
                _events.update { (it + e).takeLast(2_000) }
            }
        }
    }

    fun send(cmd: String) {
        if (cmd.isBlank()) return
        viewModelScope.launch { session.sendAndAwait(cmd.trim().uppercase()) }
    }

    fun clear() { _events.value = emptyList() }
}

@Composable
fun RawConsoleScreen(vm: RawConsoleViewModel = hiltViewModel()) {
    val events by vm.events.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Raw Console", style = MaterialTheme.typography.titleLarge)
            Button(onClick = vm::clear) { Text("Clear") }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            items(events) { e ->
                val color = if (e.direction == 'W') Color(0xFFE5A100) else Color(0xFF7DB6E0)
                Text(
                    "${e.direction}  ${e.line}",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), label = { Text("Command (e.g. ATRV, 010C)") },
                singleLine = true,
            )
            Button(onClick = { vm.send(input); input = "" }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Send")
            }
        }
    }
}
