package dev.kirker.miatadash.feature.smog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.kirker.miatadash.ui.Routes
import dev.kirker.miatadash.ui.components.ScreenHeader

@Composable
fun SmogHomeScreen(nav: NavController) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader("Smog & emissions",
            "Monitor readiness, watch O2 sensors, read DTCs")

        Item("Readiness monitors",
            "Continuous + drive-cycle monitor status (Mode 01 PID 01 / 41)") { nav.navigate(Routes.SMOG_READINESS) }
        Item("Catalyst efficiency",
            "Pre/post O2 sensor compare + Mode 06 catalyst monitor results") { nav.navigate(Routes.SMOG_CAT) }
        Item("DTCs",
            "Active and pending diagnostic trouble codes (Mode 03 / 07)") { nav.navigate(Routes.SMOG_DTC) }
    }
}

@Composable
private fun Item(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
    }
    HorizontalDivider()
}
