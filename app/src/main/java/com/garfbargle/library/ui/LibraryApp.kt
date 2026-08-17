package com.garfbargle.library.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.garfbargle.library.BuildConfig
import com.garfbargle.library.auth.GitHubTokenStore
import com.garfbargle.library.data.AppEntry
import com.garfbargle.library.data.CatalogLoadResult
import com.garfbargle.library.data.CatalogRepository
import com.garfbargle.library.data.DeviceApps
import com.garfbargle.library.data.InstalledState
import com.garfbargle.library.data.TrustKind
import com.garfbargle.library.install.AppInstaller
import com.garfbargle.library.install.InstallEvent
import com.garfbargle.library.install.InstallEvents
import com.garfbargle.library.install.InstallState
import com.garfbargle.library.ui.theme.Acid
import com.garfbargle.library.ui.theme.Ink
import com.garfbargle.library.ui.theme.SurfaceRaised
import com.garfbargle.library.ui.theme.TextPrimary
import com.garfbargle.library.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private enum class Tab { LIBRARY, APPS, SETTINGS }

@Composable
fun LibraryApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { GitHubTokenStore(context) }
    val repository = remember { CatalogRepository(context, tokenStore) }
    val deviceApps = remember { DeviceApps(context) }
    val installer = remember { AppInstaller(context, tokenStore) }

    var load by remember { mutableStateOf<CatalogLoadResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.LIBRARY) }
    var selected by remember { mutableStateOf<AppEntry?>(null) }
    var pendingPermission by remember { mutableStateOf<AppEntry?>(null) }
    var replacement by remember { mutableStateOf<AppEntry?>(null) }
    var installAfterRemoval by remember { mutableStateOf<AppEntry?>(null) }
    var hasToken by remember { mutableStateOf(tokenStore.hasToken()) }
    val installed = remember { mutableStateMapOf<String, InstalledState>() }
    val installStates = remember { mutableStateMapOf<String, InstallState>() }

    fun beginInstall(app: AppEntry) {
        scope.launch {
            installer.install(app) { state -> installStates[app.packageName] = state }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingPermission?.let { app ->
            if (installer.canRequestPackageInstalls()) beginInstall(app)
            else installStates[app.packageName] = InstallState.Failed("Allow Library to install apps, then try again.")
        }
        pendingPermission = null
    }

    val uninstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        replacement?.let { app ->
            val current = deviceApps.stateFor(app.packageName)
            installed[app.packageName] = current
            if (!current.installed) {
                installStates[app.packageName] = InstallState.Idle
                installAfterRemoval = app
            }
        }
        replacement = null
    }

    fun requestInstall(app: AppEntry) {
        if (app.requiresGitHubAuth && !hasToken) {
            selected = null
            tab = Tab.SETTINGS
            return
        }
        val current = installed[app.packageName] ?: deviceApps.stateFor(app.packageName).also {
            installed[app.packageName] = it
        }
        if (current.requiresReplacement(app)) {
            replacement = app
            return
        }
        if (installer.canRequestPackageInstalls()) {
            beginInstall(app)
        } else {
            pendingPermission = app
            installStates[app.packageName] = InstallState.AwaitingPermission
            permissionLauncher.launch(installer.unknownSourcesIntent())
        }
    }

    LaunchedEffect(installAfterRemoval?.id) {
        installAfterRemoval?.let { app ->
            installAfterRemoval = null
            requestInstall(app)
        }
    }

    LaunchedEffect(refreshKey) {
        refreshing = true
        runCatching { repository.load(preferRemote = true) }
            .onSuccess { result ->
                load = result
                error = null
                installed.clear()
                result.catalog.apps.forEach { installed[it.packageName] = deviceApps.stateFor(it.packageName) }
            }
            .onFailure { error = it.message ?: "Catalog unavailable" }
        refreshing = false
    }

    LaunchedEffect(Unit) {
        InstallEvents.events.collect { event ->
            when (event) {
                is InstallEvent.Success -> {
                    installStates[event.packageName] = InstallState.Success
                    installed[event.packageName] = deviceApps.stateFor(event.packageName)
                }
                is InstallEvent.Failure -> installStates[event.packageName] = InstallState.Failed(event.message)
            }
        }
    }

    Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
        selected?.let { app ->
            AppDetail(
                app = app,
                installed = installed[app.packageName] ?: InstalledState(false),
                state = installStates[app.packageName] ?: InstallState.Idle,
                onBack = { selected = null },
                onInstall = { requestInstall(app) },
                onOpen = { openApp(context, app.packageName) },
                onSource = { app.sourceUrl?.let { openUrl(context, it) } },
                onRelease = { app.releaseUrl?.let { openUrl(context, it) } },
                loadReadme = { repository.readmeFor(app) }
            )
        } ?: MainShell(
            load = load,
            error = error,
            refreshing = refreshing,
            tab = tab,
            installed = installed,
            installStates = installStates,
            hasToken = hasToken,
            onTab = { tab = it },
            onOpen = { selected = it },
            onLaunch = { openApp(context, it.packageName) },
            onRefresh = { refreshKey++ },
            onInstall = ::requestInstall,
            onSaveToken = {
                tokenStore.saveToken(it)
                hasToken = true
                refreshKey++
            },
            onClearToken = {
                tokenStore.clear()
                hasToken = false
                refreshKey++
            }
        )
    }

    replacement?.let { app ->
        AlertDialog(
            onDismissRequest = { replacement = null },
            title = { Text("Replace ${app.name}?") },
            text = {
                Text(
                    "The installed copy uses a different signing key, so Android cannot update it in place. Remove the current app first, then Library will continue with this build. Removing an app may also remove its local data."
                )
            },
            dismissButton = { TextButton(onClick = { replacement = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = { uninstallLauncher.launch(installer.uninstallIntent(app.packageName)) }) {
                    Text("Remove current app")
                }
            }
        )
    }
}

@Composable
private fun MainShell(
    load: CatalogLoadResult?,
    error: String?,
    refreshing: Boolean,
    tab: Tab,
    installed: Map<String, InstalledState>,
    installStates: Map<String, InstallState>,
    hasToken: Boolean,
    onTab: (Tab) -> Unit,
    onOpen: (AppEntry) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onRefresh: () -> Unit,
    onInstall: (AppEntry) -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit
) {
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D0E10)) {
                NavItem(Tab.LIBRARY, tab, "Library", Icons.Default.LibraryBooks, onTab)
                NavItem(Tab.APPS, tab, "Apps", Icons.Default.Apps, onTab)
                NavItem(Tab.SETTINGS, tab, "Settings", Icons.Default.Settings, onTab)
            }
        }
    ) { padding ->
        if (load == null && error == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Acid)
            }
            return@Scaffold
        }
        if (load == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Library couldn't open.", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(error.orEmpty(), color = TextSecondary)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onRefresh) { Text("Try again") }
                }
            }
            return@Scaffold
        }

        val catalog = load.catalog
        when (tab) {
            Tab.LIBRARY -> LibraryScreen(
                catalog.apps,
                load.warning,
                refreshing,
                onOpen,
                onLaunch,
                onRefresh,
                onInstall,
                installed,
                installStates
            )
            Tab.APPS -> AppsScreen(
                catalog.apps,
                onOpen,
                onLaunch,
                onInstall,
                installed,
                installStates,
                padding
            )
            Tab.SETTINGS -> SettingsScreen(hasToken, load.warning, onSaveToken, onClearToken, onRefresh, padding)
        }
    }
}

@Composable
private fun RowScope.NavItem(
    tab: Tab,
    current: Tab,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onTab: (Tab) -> Unit
) {
    NavigationBarItem(
        selected = tab == current,
        onClick = { onTab(tab) },
        icon = { Icon(icon, null) },
        label = { Text(label, fontSize = 10.sp) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Ink,
            indicatorColor = Acid,
            unselectedIconColor = TextSecondary,
            selectedTextColor = TextPrimary,
            unselectedTextColor = TextSecondary
        )
    )
}

@Composable
private fun LibraryScreen(
    apps: List<AppEntry>,
    warning: String?,
    refreshing: Boolean,
    onOpen: (AppEntry) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onRefresh: () -> Unit,
    onInstall: (AppEntry) -> Unit,
    installed: Map<String, InstalledState>,
    installStates: Map<String, InstallState>
) {
    var query by remember { mutableStateOf("") }
    val visible = apps.filter {
        query.isBlank() || it.name.contains(query, true) || it.tagline.contains(query, true) ||
            it.category.contains(query, true) || it.developer.contains(query, true)
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 54.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Library",
                    color = TextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), color = Acid, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "Refresh", tint = TextSecondary)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search apps") }
            )
        }
        warning?.let { item { Notice(it) } }
        if (visible.isEmpty()) {
            item {
                EmptyCard(
                    if (apps.isEmpty()) "No apps yet" else "No match",
                    if (apps.isEmpty()) "Publish a stable GitHub Release with a standalone APK and it will appear after the next catalog refresh." else "Try another search."
                )
            }
        }
        items(visible, key = { it.id }) { app ->
            AppCard(
                app,
                installed[app.packageName] ?: InstalledState(false),
                installStates[app.packageName] ?: InstallState.Idle,
                onOpen,
                onLaunch,
                onInstall
            )
        }
    }
}

@Composable
private fun AppsScreen(
    apps: List<AppEntry>,
    onOpen: (AppEntry) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onInstall: (AppEntry) -> Unit,
    installed: Map<String, InstalledState>,
    installStates: Map<String, InstallState>,
    padding: PaddingValues
) {
    var updatesOnly by remember { mutableStateOf(false) }
    val installedApps = apps.filter { installed[it.packageName]?.installed == true }
    val visible = if (updatesOnly) {
        installedApps.filter { installed[it.packageName]?.hasUpdate(it) == true }
    } else {
        installedApps
    }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Apps", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Installed on this device.", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            FilterChip(
                selected = updatesOnly,
                onClick = { updatesOnly = !updatesOnly },
                label = { Text("Updates only") },
                leadingIcon = {
                    Icon(
                        if (updatesOnly) Icons.Default.CheckCircle else Icons.Default.SystemUpdate,
                        null,
                        modifier = Modifier.size(17.dp)
                    )
                }
            )
        }
        if (visible.isEmpty()) {
            item {
                EmptyCard(
                    if (updatesOnly) "All current" else "No apps yet",
                    if (updatesOnly) "No installed apps have updates right now." else "Installed catalog apps will collect here."
                )
            }
        }
        items(visible, key = { it.id }) { app ->
            AppCard(
                app,
                installed[app.packageName] ?: InstalledState(false),
                installStates[app.packageName] ?: InstallState.Idle,
                onOpen,
                onLaunch,
                onInstall
            )
        }
    }
}

@Composable
private fun AppCard(
    app: AppEntry,
    installed: InstalledState,
    state: InstallState,
    onOpen: (AppEntry) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onInstall: (AppEntry) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceRaised)
            .clickable { onOpen(app) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app, 58)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.requiresGitHubAuth) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Lock, null, tint = TextSecondary, modifier = Modifier.size(13.dp))
                }
            }
            Text(app.tagline, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val replacementRequired = installed.requiresReplacement(app)
            val status = when {
                replacementRequired -> "Different signer · replace required"
                installed.hasUpdate(app) -> "${installed.versionName ?: "Installed"} → ${app.versionName}"
                installed.installed -> "Installed · ${installed.versionName ?: app.versionName}"
                else -> "${trustLabel(app.trust)} · ${app.versionName}"
            }
            Text(
                status,
                color = if (replacementRequired) Color(0xFFFFB86B) else Color(0xFF73757D),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        InstallButton(app, installed, state, onLaunch, onInstall)
    }
}

@Composable
private fun InstallButton(
    app: AppEntry,
    installed: InstalledState,
    state: InstallState,
    onLaunch: (AppEntry) -> Unit,
    onInstall: (AppEntry) -> Unit
) {
    val busy = state.isBusy()
    val label = when (state) {
        is InstallState.Downloading -> state.progress?.let { "${(it * 100).toInt()}%" } ?: "DOWN"
        InstallState.Verifying -> "CHECK"
        InstallState.Installing -> "INSTALL"
        InstallState.AwaitingPermission -> "ALLOW"
        else -> when {
            installed.requiresReplacement(app) -> "REPLACE"
            installed.hasUpdate(app) -> "UPDATE"
            installed.installed -> "OPEN"
            else -> "GET"
        }
    }
    val passive = installed.installed && !installed.hasUpdate(app)
    Button(
        onClick = {
            if (installed.installed && !installed.hasUpdate(app)) onLaunch(app)
            else onInstall(app)
        },
        enabled = !busy,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (passive) Color(0xFF26282C) else Acid,
            contentColor = if (passive) TextPrimary else Ink
        )
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun AppDetail(
    app: AppEntry,
    installed: InstalledState,
    state: InstallState,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onSource: () -> Unit,
    onRelease: () -> Unit,
    loadReadme: suspend () -> String?
) {
    var readme by remember(app.id) { mutableStateOf<String?>(null) }
    var readmeLoaded by remember(app.id) { mutableStateOf(false) }

    LaunchedEffect(app.id) {
        readmeLoaded = false
        readme = runCatching { loadReadme() }.getOrNull()
        readmeLoaded = true
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 82.dp, bottom = 70.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, 84)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(app.tagline, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            item {
                val busy = state.isBusy()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = if (installed.installed && !installed.hasUpdate(app)) onOpen else onInstall,
                        enabled = !busy,
                        modifier = Modifier.height(44.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 22.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (installed.installed && !installed.hasUpdate(app)) Color(0xFF26282C) else Acid,
                            contentColor = if (installed.installed && !installed.hasUpdate(app)) TextPrimary else Ink
                        )
                    ) {
                        Text(
                            when {
                                state is InstallState.Downloading -> state.progress?.let { "${(it * 100).toInt()}%" } ?: "Downloading"
                                state is InstallState.Verifying -> "Verifying"
                                state is InstallState.Installing -> "Installing"
                                state is InstallState.AwaitingPermission -> "Allow install"
                                installed.requiresReplacement(app) -> "Replace"
                                installed.hasUpdate(app) -> "Update"
                                installed.installed -> "Open"
                                else -> "Install"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                when (state) {
                    is InstallState.Downloading -> {
                        Spacer(Modifier.height(10.dp))
                        DownloadProgress(state)
                    }
                    is InstallState.Failed -> {
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    else -> Unit
                }
            }
            item { Metadata(app) }
            if (app.sourceUrl != null || app.releaseUrl != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (app.sourceUrl != null) {
                            OutlinedButton(onClick = onSource, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Default.Source, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Source")
                            }
                        }
                        if (app.releaseUrl != null) {
                            OutlinedButton(onClick = onRelease, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Release")
                            }
                        }
                    }
                }
            }
            if (app.changelog.isNotEmpty()) item { ReleaseNotes(app) }
            item { ReadmeSection(app, readme, readmeLoaded) }
            item { SecurityDisclosure(app, installed) }
            item {
                Text(app.packageName, color = Color(0xFF62646B), fontSize = 10.sp)
                Text("by ${app.developer}", color = Color(0xFF62646B), fontSize = 10.sp)
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp),
            shape = CircleShape,
            color = Color(0xE61A1B1E),
            shadowElevation = 8.dp
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
            }
        }
    }
}

@Composable
private fun ReleaseNotes(app: AppEntry) {
    var showAll by remember(app.id) { mutableStateOf(false) }
    val visible = if (showAll) app.changelog else app.changelog.take(5)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF121315)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, null, tint = TextSecondary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Release notes", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(app.releaseTag ?: app.versionName, color = TextSecondary, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        visible.forEach { note ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", color = Acid, modifier = Modifier.width(16.dp))
                Text(note, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        if (app.changelog.size > 5) {
            TextButton(
                onClick = { showAll = !showAll },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)
            ) {
                Text(if (showAll) "Show less" else "View more")
            }
        }
    }
}

@Composable
private fun ReadmeSection(app: AppEntry, readme: String?, readmeLoaded: Boolean) {
    Column {
        Text("README", color = Color(0xFF6A6C73), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        when {
            !readmeLoaded -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = TextSecondary, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(10.dp))
                Text("Loading from ${app.repository ?: "repository"}…", color = TextSecondary, fontSize = 12.sp)
            }
            !readme.isNullOrBlank() -> ReadmePanel(readme)
            else -> ReadmePanel(app.description, markdown = false)
        }
    }
}

@Composable
private fun ReadmePanel(content: String, markdown: Boolean = true) {
    val scrollState = rememberScrollState()
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF111214))
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        if (markdown) ReadmeContent(content)
        else Text(content, color = TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun AppIcon(app: AppEntry, size: Int) {
    val context = LocalContext.current
    val image = remember(app.icon?.dataBase64, app.packageName) {
        val catalogImage = app.icon?.dataBase64?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        catalogImage ?: runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap()
        }.getOrNull()
    }

    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp)).background(Color(app.accent)),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(image, app.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                app.name.take(1).uppercase(),
                color = Ink,
                fontSize = (size * 0.42f).sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun DownloadProgress(state: InstallState.Downloading) {
    Box(
        Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0xFF25272B))
    ) {
        state.progress?.let { progress ->
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Acid))
        }
    }
    Spacer(Modifier.height(7.dp))
    val amount = if (state.totalBytes != null) {
        "${formatBytes(state.bytesDownloaded)} of ${formatBytes(state.totalBytes)}"
    } else {
        formatBytes(state.bytesDownloaded)
    }
    Text("$amount · downloading", color = TextSecondary, fontSize = 11.sp)
}

@Composable
private fun Metadata(app: AppEntry) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF111214)).padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Metric("VERSION", app.versionName)
        Metric("ANDROID", "${app.minSdk}+")
        Metric("SIZE", formatBytes(app.sizeBytes))
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF6A6C73), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecurityDisclosure(app: AppEntry, installed: InstalledState) {
    DisclosureCard(
        title = "Security & provenance",
        subtitle = trustLabel(app.trust),
        icon = Icons.Default.Security
    ) {
        Text(
            "Library verifies the APK hash, package identity, version code, and signing certificate before installation.",
            color = TextSecondary,
            fontSize = 13.sp
        )
        app.signingCertSha256?.let {
            Spacer(Modifier.height(10.dp))
            Text("SIGNER  ${shortHash(it)}", color = Color(0xFF85878E), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        app.preferredArtifact()?.sha256?.takeIf { it.isNotBlank() }?.let {
            Text("APK       ${shortHash(it)}", color = Color(0xFF85878E), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        if (installed.requiresReplacement(app)) {
            Spacer(Modifier.height(10.dp))
            Text(
                "The installed copy has a different signer and must be removed before this update can be installed.",
                color = Color(0xFFFFB86B),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DisclosureCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF121315))
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextSecondary)
        }
        if (expanded) {
            HorizontalDivider(color = Color(0xFF23252A))
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

private enum class ReadmeKind { HEADING, PARAGRAPH, BULLET, QUOTE, CODE, DIVIDER }
private data class ReadmeBlock(val kind: ReadmeKind, val text: String, val level: Int = 0)

private fun parseReadme(markdown: String): List<ReadmeBlock> {
    val blocks = mutableListOf<ReadmeBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            cleanInlineMarkdown(paragraph.joinToString(" ")).takeIf { it.isNotBlank() }?.let {
                blocks += ReadmeBlock(ReadmeKind.PARAGRAPH, it)
            }
            paragraph.clear()
        }
    }

    fun flushCode() {
        if (code.isNotEmpty()) {
            blocks += ReadmeBlock(ReadmeKind.CODE, code.joinToString("\n").trimEnd())
            code.clear()
        }
    }

    markdown.lines().forEach { raw ->
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            if (inCode) flushCode()
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            code += raw
            return@forEach
        }
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            flushParagraph()
            return@forEach
        }
        if (trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.contains("</")) {
            flushParagraph()
            return@forEach
        }
        val heading = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks += ReadmeBlock(ReadmeKind.HEADING, cleanInlineMarkdown(heading.groupValues[2]), heading.groupValues[1].length)
            return@forEach
        }
        if (Regex("^[-*_]{3,}$").matches(trimmed)) {
            flushParagraph()
            blocks += ReadmeBlock(ReadmeKind.DIVIDER, "")
            return@forEach
        }
        val bullet = Regex("^(?:[-*+] |\\d+[.)] )(.+)$").find(trimmed)
        if (bullet != null) {
            flushParagraph()
            blocks += ReadmeBlock(ReadmeKind.BULLET, cleanInlineMarkdown(bullet.groupValues[1]))
            return@forEach
        }
        if (trimmed.startsWith(">")) {
            flushParagraph()
            blocks += ReadmeBlock(ReadmeKind.QUOTE, cleanInlineMarkdown(trimmed.removePrefix(">").trim()))
            return@forEach
        }
        paragraph += trimmed
    }
    flushParagraph()
    flushCode()
    return blocks
}

private fun cleanInlineMarkdown(value: String): String = value
    .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1")
    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
    .replace(Regex("<[^>]+>"), "")
    .replace("**", "")
    .replace("__", "")
    .replace("~~", "")
    .replace("`", "")
    .trim()

@Composable
private fun ReadmeContent(markdown: String) {
    val blocks = remember(markdown) { parseReadme(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                ReadmeKind.HEADING -> Text(
                    block.text,
                    color = TextPrimary,
                    fontSize = when (block.level) { 1 -> 24.sp; 2 -> 20.sp; 3 -> 17.sp; else -> 15.sp },
                    fontWeight = FontWeight.Bold,
                    lineHeight = when (block.level) { 1 -> 30.sp; 2 -> 26.sp; else -> 22.sp },
                    modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp)
                )
                ReadmeKind.PARAGRAPH -> Text(block.text, color = TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
                ReadmeKind.BULLET -> Row {
                    Text("•", color = Acid, modifier = Modifier.width(18.dp))
                    Text(block.text, color = TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
                }
                ReadmeKind.QUOTE -> Text(
                    block.text,
                    color = Color(0xFF9A9DA6),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF111315)).padding(12.dp)
                )
                ReadmeKind.CODE -> Text(
                    block.text,
                    color = Color(0xFFC7CAD2),
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E0F11)).padding(13.dp)
                )
                ReadmeKind.DIVIDER -> HorizontalDivider(color = Color(0xFF24262B))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF131517)).padding(18.dp)) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SettingsScreen(
    hasToken: Boolean,
    warning: String?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    padding: PaddingValues
) {
    var token by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Settings", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Private access stays on this device.", color = TextSecondary)
        }
        item {
            SectionCard("GitHub") {
                Text(
                    if (hasToken) "Connected for private repositories and release assets." else "Connect a fine-grained token with read access to the private repositories you want in Library.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                if (!hasToken) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("GitHub token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { if (token.isNotBlank()) { onSave(token); token = "" } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Connect GitHub") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Acid)
                        Spacer(Modifier.width(8.dp))
                        Text("Stored with Android Keystore", color = TextPrimary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onClear,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292B30)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disconnect") }
                }
            }
        }
        item {
            SectionCard("Catalog") {
                Text(BuildConfig.CATALOG_REPOSITORY, color = TextSecondary, fontSize = 12.sp)
                warning?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }
            }
        }
        item {
            SectionCard("Library ${BuildConfig.VERSION_NAME}") {
                Text(
                    "Library verifies every APK before installation and keeps each app's signing identity intact.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun Notice(message: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF211D14)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Lock, null, tint = Color(0xFFFFC766), modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Text(message, color = Color(0xFFD8C8A7), fontSize = 11.sp)
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF121315)).padding(24.dp)) {
        Icon(Icons.Default.LibraryBooks, null, tint = Acid)
        Spacer(Modifier.height(20.dp))
        Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = TextSecondary, fontSize = 13.sp)
    }
}

private fun InstallState.isBusy(): Boolean =
    this is InstallState.Downloading || this is InstallState.Verifying || this is InstallState.Installing || this is InstallState.AwaitingPermission

private fun trustLabel(kind: TrustKind) = when (kind) {
    TrustKind.DEVELOPER_SIGNED -> "Developer signed"
    TrustKind.LIBRARY_BUILT -> "Built by Library"
    TrustKind.BINARY -> "Binary release"
}

private fun shortHash(hash: String) = hash.replace(":", "").chunked(4).take(8).joinToString(" ") + " …"
private fun formatBytes(bytes: Long): String = if (bytes <= 0) "—" else "%.1f MB".format(bytes / 1_048_576.0)
private fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
private fun openApp(context: Context, packageName: String) {
    context.packageManager.getLaunchIntentForPackage(packageName)?.let {
        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
