package dev.nercone.typefacedroplet.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

/**
 * The application: its destinations, where the user is, and what any screen can open (the engine
 * log, the restricted-root dialog and the file picker of fonts), in the frame shared with Replay
 * Integrity (`Frame`), which adds the floating action button of the fonts and of the
 * customization.
 */
object DropletApp {
    enum class Destination(override val title: Int, override val selectedIcon: ImageVector, override val icon: ImageVector) : Frame.Destination {
        HOME(R.string.tab_home, Icons.Filled.Home, Icons.Outlined.Home),
        FONTS(R.string.tab_fonts, Icons.Filled.FontDownload, Icons.Outlined.FontDownload),
        CUSTOMIZE(R.string.tab_customize, Icons.Filled.Tune, Icons.Outlined.Tune),
        SETTINGS(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
    }

    /** Where the user is: a destination, and the page of the settings when one is open. */
    data class Route(val destination: Destination, val page: SettingsScreen.Page?)

    /** Opens `destination`, at `page` when it is the settings. */
    fun interface Navigator {
        fun open(destination: Destination, page: SettingsScreen.Page?)
    }

    /** The MIME types offered when importing a font. */
    val FONT_TYPES = arrayOf("font/*", "application/font-sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")

    @Composable
    fun Content(model: AppViewModel) {
        val state by model.state.collectAsStateWithLifecycle()
        val config by model.config.collectAsStateWithLifecycle()
        val fonts by model.fonts.collectAsStateWithLifecycle()
        val settings by model.settings.collectAsStateWithLifecycle()
        val data = remember(state, config, fonts, settings) { ScreenData(state, config, fonts, settings) }
        var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
        var page by rememberSaveable { mutableStateOf<SettingsScreen.Page?>(null) }
        val route = Route(destination, page.takeIf { destination == Destination.SETTINGS })
        // Choosing a destination opens it at its start (the list of the settings, also when it
        // is chosen again from one of its pages); a shortcut may open a page of the settings.
        val navigator = Navigator { target, targetPage ->
            page = targetPage.takeIf { target == Destination.SETTINGS }
            destination = target
        }
        // Back leaves a page of the settings first, then returns to the home screen, and only
        // from there leaves the application.
        BackHandler(enabled = route.page != null) { page = null }
        BackHandler(enabled = route.page == null && destination != Destination.HOME) { navigator.open(Destination.HOME, null) }

        val customize = remember { CustomizeScreen.State() }
        val noFilePicker = stringResource(R.string.no_file_picker)
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importFont) }
        val import = {
            // A system without a documents application has no activity for this request.
            try {
                importer.launch(FONT_TYPES)
            } catch (error: ActivityNotFoundException) {
                model.fail(noFilePicker)
            }
        }
        NotificationRequest(data, model)

        Frame.Content(
            destinations = Destination.entries,
            destination = destination,
            route = route,
            title = title(route),
            operation = state.operation?.let { Labels.operation(it) },
            onNavigate = { navigator.open(it, null) },
            onBack = if (route.page != null) ({ page = null }) else null,
            floatingButton = { expanded ->
                when (destination) {
                    Destination.FONTS -> ExtendedFloatingActionButton(
                        onClick = import,
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(stringResource(R.string.fonts_import)) },
                        expanded = expanded,
                    )
                    Destination.CUSTOMIZE -> CustomizeScreen.FloatingButton(customize, config != null)
                    else -> Unit
                }
            },
        ) { shown ->
            when (shown.destination) {
                Destination.HOME -> HomeScreen.Content(data, model, navigator)
                Destination.FONTS -> FontsScreen.Content(data, model)
                Destination.CUSTOMIZE -> CustomizeScreen.Content(data, model, customize)
                Destination.SETTINGS -> SettingsScreen.Content(data, model, shown.page) { page = it }
            }
        }

        if (state.logShown) Frame.Log(state.log) { model.showLog(false) }
        (state.outcome as? Outcome.Failed)?.takeIf { it.restricted }?.let {
            Frame.Restricted(it.text, onRecheck = { model.dismissOutcome(); model.recheck() }, onDismiss = model::dismissOutcome)
        }
    }

    @Composable
    fun title(route: Route): String = when {
        route.page != null -> stringResource(route.page.title)
        route.destination == Destination.HOME -> stringResource(R.string.app_name)
        else -> stringResource(route.destination.title)
    }

    /**
     * Asks for the notification permission once, after the fonts are first applied:
     * notifications report failures of the application after a boot, which only happens from
     * then on.
     */
    @Composable
    fun NotificationRequest(data: ScreenData, model: AppViewModel) {
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val context = LocalContext.current
        val outcome = data.state.outcome
        LaunchedEffect(outcome) {
            if (outcome is Outcome.Applied && !data.settings.notificationsAsked &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                model.setNotificationsAsked()
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
