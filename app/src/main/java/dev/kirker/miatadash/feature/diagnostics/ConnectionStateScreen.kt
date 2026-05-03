package dev.kirker.miatadash.feature.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
class ConnectionStateViewModel @Inject constructor(
    val session: ObdSession,
) : ViewModel() {
    private val _transitions = MutableStateFlow<List<ObdSession.Transition>>(emptyList())
    val transitions: StateFlow<List<ObdSession.Transition>> = _transitions.asStateFlow()
    val phase = session.phase

    init {
        viewModelScope.launch {
            session.transitions.collect { t -> _transitions.update { (it + t).takeLast(200) } }
        }
    }
}

@Composable
fun ConnectionStateScreen(vm: ConnectionStateViewModel = hiltViewModel()) {
    val transitions by vm.transitions.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Connection State", style = MaterialTheme.typography.titleLarge)
        Text("Current: $phase", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 8.dp))
        Text("Recent transitions", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(transitions.reversed()) { t ->
                val dt = "+${t.tsMs % 100_000}ms"
                Text(
                    "%s  %s → %s  %s".format(dt.padEnd(10), t.from.name, t.to.name, t.note ?: ""),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
