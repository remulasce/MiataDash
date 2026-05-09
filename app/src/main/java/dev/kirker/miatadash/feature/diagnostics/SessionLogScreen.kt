package dev.kirker.miatadash.feature.diagnostics

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kirker.miatadash.ui.components.ScreenHeader
import java.io.File

/**
 * Diagnostic screen that lets you read the persisted session log files without needing ADB.
 *
 * Up to 10 sessions are kept (session_0 = current … session_9 = oldest). The count is
 * controlled by [dev.kirker.miatadash.core.logging.FileLogTree]. This screen discovers
 * which files actually exist so the chip list shrinks naturally on a fresh install.
 * Each file is capped at 4 MB by FileLogTree.
 *
 * Lines containing "W/" or "E/" are highlighted in amber/red respectively so errors are
 * easy to spot when scrolling quickly.
 *
 * To pull logs via ADB:
 *   `adb pull /data/data/dev.kirker.miatadash/files/logs/`
 */
@Composable
fun SessionLogScreen() {
    val ctx = LocalContext.current

    // Discover which session files actually exist so the chip list reflects reality.
    // Labels: index 0 = "Current", rest = "−N" (sessions ago).
    val sessions = remember(ctx) {
        (0 until 10)
            .map { "session_$it.log" }
            .filter { File(ctx.filesDir, "logs/$it").exists() }
    }
    val labels = sessions.mapIndexed { i, _ -> if (i == 0) "Current" else "−$i" }
    var selected by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Session Logs", "WARN/INFO/ERROR from the last ${sessions.size} sessions")

        // Session selector chips
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEachIndexed { i, label ->
                FilterChip(
                    selected = selected == i,
                    onClick  = { selected = i },
                    label    = { Text(label) },
                )
            }
        }

        val lines = remember(selected) {
            readLogFile(ctx, sessions[selected])
        }

        if (lines.isEmpty()) {
            Text(
                "No log data for this session yet.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
            return@Column
        }

        val listState = rememberLazyListState()
        LazyColumn(
            state     = listState,
            modifier  = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            items(lines) { line ->
                val color = when {
                    " E/" in line -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                    " W/" in line -> androidx.compose.ui.graphics.Color(0xFFE5A100).copy(alpha = 0.15f)
                    else          -> androidx.compose.ui.graphics.Color.Transparent
                }
                val textColor = when {
                    " E/" in line -> MaterialTheme.colorScheme.error
                    " W/" in line -> MaterialTheme.colorScheme.onBackground
                    else          -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                }
                Text(
                    text     = line,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    style    = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color    = textColor,
                )
            }
        }
    }
}

private fun readLogFile(ctx: Context, name: String): List<String> {
    val file = File(ctx.filesDir, "logs/$name")
    if (!file.exists()) return emptyList()
    return try {
        file.readLines()
    } catch (_: Exception) {
        listOf("(error reading log file)")
    }
}
