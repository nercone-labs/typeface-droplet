package dev.nercone.typefacedroplet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nercone.typefacedroplet.R

class MainActivity : ComponentActivity() {
    val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DropletTheme.Content { DropletApp.Content(model) } }
    }
}

/** The application frame: top bar, navigation bar, floating action button and the screen of the destination. */
object DropletApp {
    enum class Destination(val title: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
        HOME(R.string.tab_home, Icons.Filled.Home, Icons.Outlined.Home),
        FONTS(R.string.tab_fonts, Icons.Filled.FontDownload, Icons.Outlined.FontDownload),
        CUSTOMIZE(R.string.tab_customize, Icons.Filled.Tune, Icons.Outlined.Tune),
        SETTINGS(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
    }

    /** The MIME types offered when importing a font. */
    val FONT_TYPES = arrayOf("font/*", "application/font-sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun Content(model: AppViewModel) {
        val state by model.state.collectAsStateWithLifecycle()
        val config by model.config.collectAsStateWithLifecycle()
        val fonts by model.fonts.collectAsStateWithLifecycle()
        val settings by model.settings.collectAsStateWithLifecycle()
        var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
        val customize = remember { CustomizeScreen.State() }
        val snackbar = remember { SnackbarHostState() }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importFont) }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val context = LocalContext.current
        val outcome = state.outcome

        // Notifications only matter once fonts are applied: they report problems after a boot.
        LaunchedEffect(outcome) {
            if (outcome is Outcome.Applied && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // The home screen shows outcomes itself; elsewhere a failure appears as a snackbar.
        val failure = (outcome as? Outcome.Failed)?.takeIf { !it.restricted && destination != Destination.HOME }
        LaunchedEffect(failure) {
            failure?.let {
                snackbar.showSnackbar(it.text, withDismissAction = true)
                model.dismissOutcome()
            }
        }

        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBar = {
                Column {
                    LargeFlexibleTopAppBar(
                        title = { Text(stringResource(if (destination == Destination.HOME) R.string.app_name else destination.title)) },
                        subtitle = state.operation?.let { { Text(operationText(it)) } },
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                    if (state.operation != null) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Widgets.SCREEN_PADDING))
                }
            },
            bottomBar = {
                ShortNavigationBar {
                    Destination.entries.forEach { entry ->
                        val selected = destination == entry
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = { destination = entry },
                            icon = { Icon(if (selected) entry.selectedIcon else entry.icon, null) },
                            label = { Text(stringResource(entry.title)) },
                        )
                    }
                }
            },
            floatingActionButton = {
                when (destination) {
                    Destination.FONTS -> ExtendedFloatingActionButton(
                        onClick = { importer.launch(FONT_TYPES) },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(stringResource(R.string.fonts_import)) },
                        expanded = scrollBehavior.state.collapsedFraction < 0.5f,
                    )
                    Destination.CUSTOMIZE -> CustomizeScreen.FloatingButton(customize, config != null)
                    else -> {}
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            AnimatedContent(
                targetState = destination,
                transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                modifier = Modifier.fillMaxSize().padding(padding),
                label = "destination",
            ) { shown ->
                Box(Modifier.fillMaxSize()) {
                    when (shown) {
                        Destination.HOME -> HomeScreen.Content(state, config, fonts, settings, model) { destination = it }
                        Destination.FONTS -> FontsScreen.Content(state, config, fonts, settings, model)
                        Destination.CUSTOMIZE -> CustomizeScreen.Content(state, config, fonts, settings, model, customize)
                        Destination.SETTINGS -> SettingsScreen.Content(state, config, settings, model)
                    }
                }
            }
        }
        (outcome as? Outcome.Failed)?.takeIf { it.restricted }?.let { Restricted(it, onRecheck = { model.dismissOutcome(); model.refresh() }, onDismiss = model::dismissOutcome) }
    }

    /** Explains that the root manager restricts root, and how to grant it fully. */
    @Composable
    fun Restricted(failure: Outcome.Failed, onRecheck: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.restricted_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restricted_text))
                    Text(failure.text, style = MaterialTheme.typography.bodySmall, color = Widgets.secondary)
                }
            },
            confirmButton = { TextButton(onClick = onRecheck) { Text(stringResource(R.string.check_again)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
        )
    }

    @Composable
    fun operationText(operation: Operation): String = stringResource(
        when (operation) {
            Operation.PREPARING -> R.string.operation_preparing
            Operation.CHECKING -> R.string.operation_checking
            Operation.APPLYING -> R.string.operation_applying
            Operation.REVERTING -> R.string.operation_reverting
            Operation.RESTARTING -> R.string.operation_restarting
            Operation.IMPORTING -> R.string.operation_importing
            Operation.LOADING_FAMILIES -> R.string.operation_loading_families
            Operation.CONFIGURING_BOOT -> R.string.operation_configuring_boot
        },
    )
}
