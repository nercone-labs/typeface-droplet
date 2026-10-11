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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nercone.typefacedroplet.R

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DropletApp.Content(model) }
    }
}

/** The application frame: theme, top bar, navigation bar and the screen of the current tab. */
object DropletApp {
    enum class Tab(val title: Int, val icon: ImageVector) {
        HOME(R.string.tab_home, Icons.Default.Home),
        FONTS(R.string.tab_fonts, Icons.Default.FontDownload),
        ROLES(R.string.tab_roles, Icons.Default.TextFormat),
        RULES(R.string.tab_rules, Icons.AutoMirrored.Filled.Rule),
        SETTINGS(R.string.tab_settings, Icons.Default.Settings),
    }

    /** Material You: the colors follow the wallpaper. */
    @Composable
    fun Theme(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        MaterialTheme(colorScheme = colors, content = content)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content(model: AppViewModel) {
        Theme {
            val state by model.state.collectAsStateWithLifecycle()
            val config by model.config.collectAsStateWithLifecycle()
            val fonts by model.library.collectAsStateWithLifecycle()
            var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
            var editingRole by rememberSaveable { mutableStateOf<String?>(null) }
            var rulesDialog by androidx.compose.runtime.remember { mutableStateOf<RulesScreen.Dialogs?>(null) }
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importFont) }
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(title = { Text(stringResource(if (tab == Tab.HOME) R.string.app_name else tab.title)) })
                        state.operation?.let { operation ->
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(operationText(operation), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Widgets.SCREEN_PADDING))
                        }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = { Icon(entry.icon, null) },
                                label = { Text(stringResource(entry.title)) },
                            )
                        }
                    }
                },
                floatingActionButton = {
                    when (tab) {
                        Tab.FONTS -> ExtendedFloatingActionButton(
                            onClick = { importer.launch(FONT_TYPES) },
                            icon = { Icon(Icons.Default.Add, null) },
                            text = { Text(stringResource(R.string.fonts_import)) },
                        )
                        Tab.ROLES -> ExtendedFloatingActionButton(
                            onClick = { editingRole = NEW_ROLE },
                            icon = { Icon(Icons.Default.Add, null) },
                            text = { Text(stringResource(R.string.role_new)) },
                        )
                        Tab.RULES -> ExtendedFloatingActionButton(
                            onClick = { rulesDialog = RulesScreen.Dialogs.EditRule(null) },
                            icon = { Icon(Icons.Default.Add, null) },
                            text = { Text(stringResource(R.string.rule_new)) },
                        )
                        else -> {}
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
                    when (tab) {
                        Tab.HOME -> HomeScreen.Content(state, model)
                        Tab.FONTS -> FontsScreen.Content(fonts, config, model.droplet.library, model, FAB_PADDING)
                        Tab.ROLES -> RolesScreen.Content(config, fonts, model, FAB_PADDING, editingRole) { editingRole = it }
                        Tab.RULES -> RulesScreen.Content(state, config, fonts, model, FAB_PADDING, rulesDialog) { rulesDialog = it }
                        Tab.SETTINGS -> SettingsScreen.Content(state, config, model)
                    }
                }
            }
            (state.message as? Message.Restricted)?.let { Restricted(it, onRecheck = model::recheck, onDismiss = model::dismissMessage) }
        }
    }

    /** Explains that the root manager restricts root, and how to grant it fully. */
    @Composable
    fun Restricted(message: Message.Restricted, onRecheck: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.restricted_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restricted_text))
                    Text(message.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = onRecheck) { Text(stringResource(R.string.restricted_recheck)) } },
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
            Operation.RESETTING -> R.string.operation_resetting
        },
    )

    /** The role name the role editor uses for a role that does not exist yet. */
    const val NEW_ROLE = ""

    /** Room below lists for the floating action button. */
    val FAB_PADDING = androidx.compose.foundation.layout.PaddingValues(bottom = androidx.compose.ui.unit.Dp(88f))

    val FONT_TYPES = arrayOf("font/*", "application/font-sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")
}
