package dev.kirker.miatadash.feature.diagnostics

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kirker.miatadash.core.obd.ObdSession
import dev.kirker.miatadash.core.transport.ReplayTransport
import dev.kirker.miatadash.core.transport.TransportKind
import dev.kirker.miatadash.core.transport.TransportSelector
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import javax.inject.Inject

@HiltViewModel
class TraceCaptureViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val session: ObdSession,
    private val transports: TransportSelector,
    private val replay: ReplayTransport,
) : ViewModel() {
    private var captureJob: Job? = null
    
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _captureFile = MutableStateFlow<File?>(null)
    val captureFile: StateFlow<File?> = _captureFile.asStateFlow()

    val tracesDir: File = File(
        ctx.getExternalFilesDir(null) ?: ctx.filesDir,
        "traces"
    ).apply { if (!exists()) mkdirs() }

    fun startCapture() {
        if (_isCapturing.value) return
        val f = File(tracesDir, "trace_${System.currentTimeMillis()}.miatatrace")
        _captureFile.value = f
        _isCapturing.value = true
        captureJob = viewModelScope.launch {
            val out = OutputStreamWriter(FileOutputStream(f))
            val origin = System.currentTimeMillis()
            session.wire.collect { e ->
                out.write("${e.tsMs - origin} ${e.direction} ${e.line}\n")
                out.flush()
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel(); captureJob = null
        _isCapturing.value = false
    }

    fun listTraces(): List<File> = tracesDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun useForReplay(file: File) {
        replay.setFile(file)
        transports.select(TransportKind.REPLAY)
    }
}

@Composable
fun TraceCaptureScreen(vm: TraceCaptureViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val files = remember(refresh) { vm.listTraces() }
    val isCapturing by vm.isCapturing.collectAsStateWithLifecycle()
    val captureFile by vm.captureFile.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Trace Capture & Replay", style = MaterialTheme.typography.titleLarge)
        Text("Saves to ${vm.tracesDir.absolutePath}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))

        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isCapturing) Button(onClick = { vm.startCapture() }) { Text("Start capture") }
            else Button(onClick = { vm.stopCapture(); refresh++ }) { Text("Stop capture") }
            Text(
                if (isCapturing) " Recording → ${captureFile?.name}" else " Idle",
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Text("Saved traces", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        if (files.isEmpty()) {
            Text("No traces yet. Start a capture, or drop Torque Pro CSVs into the folder above.",
                style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(files) { f ->
                    Column(Modifier.fillMaxWidth().clickable {
                        vm.useForReplay(f)
                    }.padding(vertical = 8.dp)) {
                        Text(f.name, style = MaterialTheme.typography.titleLarge)
                        Text("%.1f KB · %s".format(f.length() / 1024.0, java.util.Date(f.lastModified())),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }
                    HorizontalDivider()
                }
            }
            Text("Tap a trace to load it into the Replay transport.",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
