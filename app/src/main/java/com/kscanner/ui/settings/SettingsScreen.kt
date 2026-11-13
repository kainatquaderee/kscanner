package com.kscanner.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kscanner.core.imaging.ScanFilter
import com.kscanner.data.PdfPageSize
import com.kscanner.data.ScanMode
import com.kscanner.data.ThemeMode
import com.kscanner.ui.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SectionHeader("Scanning")
            SettingLabel("Default filter")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter.id == settings.defaultFilterId,
                        onClick = { viewModel.setDefaultFilter(filter.id) },
                        label = { Text(filter.label) },
                    )
                }
            }
            SwitchRow(
                title = "Recognise text automatically",
                subtitle = "Run on-device OCR on every new page",
                checked = settings.autoOcr,
                onCheckedChange = viewModel::setAutoOcr,
            )
            SettingLabel("Camera opens in")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.scanMode == mode,
                        onClick = { viewModel.setScanMode(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Automatic correction")
            SwitchRow(
                title = "Straighten the page",
                subtitle = "Turn a sideways or upside-down capture the right way up",
                checked = settings.autoRotate,
                onCheckedChange = viewModel::setAutoRotate,
            )
            SwitchRow(
                title = "Remove leftover tilt",
                subtitle = "Measure the lean of the text and level it after cropping",
                checked = settings.autoDeskew,
                onCheckedChange = viewModel::setAutoDeskew,
            )
            SwitchRow(
                title = "Choose the look automatically",
                subtitle = "Pick between colour, greyscale and shadow removal from the page itself",
                checked = settings.autoFilter,
                onCheckedChange = viewModel::setAutoFilter,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Output quality")
            SettingLabel("Image resolution")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1200 to "Standard", 2000 to "High", 2600 to "Very high", 3200 to "Maximum").forEach { (edge, label) ->
                    FilterChip(
                        selected = settings.maxImageEdge == edge,
                        onClick = { viewModel.setMaxImageEdge(edge) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                text = "${settings.maxImageEdge} px on the longest edge",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            SliderRow(
                title = "JPEG quality",
                value = settings.jpegQuality.toFloat(),
                range = 60f..100f,
                valueLabel = "${settings.jpegQuality}%",
                onChange = { viewModel.setJpegQuality(it.toInt()) },
            )
            SwitchRow(
                title = "Keep original captures",
                subtitle = "Allows re-cropping and re-filtering without quality loss",
                checked = settings.keepOriginals,
                onCheckedChange = viewModel::setKeepOriginals,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("PDF export")
            SettingLabel("Page size")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PdfPageSize.entries.forEach { size ->
                    FilterChip(
                        selected = settings.pdfPageSize == size,
                        onClick = { viewModel.setPdfPageSize(size) },
                        label = { Text(size.label) },
                    )
                }
            }
            SwitchRow(
                title = "Searchable text layer",
                subtitle = "Embed recognised text invisibly so pages can be searched and copied",
                checked = settings.pdfSearchableText,
                onCheckedChange = viewModel::setSearchableText,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Appearance")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.SYSTEM to "System",
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.DARK to "Dark",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "KScanner 1.0 · Scans are processed and stored entirely on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}