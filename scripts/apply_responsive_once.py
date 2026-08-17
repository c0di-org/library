from pathlib import Path

p = Path('app/src/main/java/com/garfbargle/library/ui/LibraryApp.kt')
s = p.read_text()

def rep(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, found {count}')
    s = s.replace(old, new)

rep('import androidx.compose.foundation.layout.Box\n', 'import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.BoxWithConstraints\n', 'BoxWithConstraints import')
rep('import androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.fillMaxSize\n', 'import androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.fillMaxHeight\nimport androidx.compose.foundation.layout.fillMaxSize\n', 'fillMaxHeight import')
rep('import androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items\n', 'import androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.layout.widthIn\nimport androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.grid.GridCells\nimport androidx.compose.foundation.lazy.grid.GridItemSpan\nimport androidx.compose.foundation.lazy.grid.LazyVerticalGrid\nimport androidx.compose.foundation.lazy.grid.items as gridItems\n', 'grid imports')
rep('import androidx.compose.material3.NavigationBarItemDefaults\n', 'import androidx.compose.material3.NavigationBarItemDefaults\nimport androidx.compose.material3.NavigationRail\nimport androidx.compose.material3.NavigationRailItem\nimport androidx.compose.material3.NavigationRailItemDefaults\n', 'rail imports')
rep('import androidx.compose.runtime.rememberCoroutineScope\nimport androidx.compose.runtime.setValue\n', 'import androidx.compose.runtime.rememberCoroutineScope\nimport androidx.compose.runtime.saveable.rememberSaveable\nimport androidx.compose.runtime.setValue\n', 'rememberSaveable import')
rep('    var tab by remember { mutableStateOf(Tab.LIBRARY) }\n    var selected by remember { mutableStateOf<AppEntry?>(null) }\n', '    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }\n    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }\n', 'saved state')
rep('    val installStates = remember { mutableStateMapOf<String, InstallState>() }\n\n    fun beginInstall(app: AppEntry) {', '    val installStates = remember { mutableStateMapOf<String, InstallState>() }\n\n    val selected = load?.catalog?.apps?.firstOrNull { it.id == selectedId }\n\n    fun beginInstall(app: AppEntry) {', 'selected lookup')
rep('            selected = null\n            tab = Tab.SETTINGS', '            selectedId = null\n            tab = Tab.SETTINGS', 'auth redirect')
rep('                onBack = { selected = null },', '                onBack = { selectedId = null },', 'detail back')
rep('            onOpen = { selected = it },', '            onOpen = { selectedId = it.id },', 'open selection')

old_main = '''@Composable
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
'''
new_main = '''@Composable
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 840.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Ink,
            bottomBar = {
                if (!wideLayout) {
                    NavigationBar(containerColor = Color(0xFF0D0E10)) {
                        NavItem(Tab.LIBRARY, tab, "Library", Icons.Default.LibraryBooks, onTab)
                        NavItem(Tab.APPS, tab, "Apps", Icons.Default.Apps, onTab)
                        NavItem(Tab.SETTINGS, tab, "Settings", Icons.Default.Settings, onTab)
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize()) {
                if (wideLayout) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight().width(88.dp),
                        containerColor = Color(0xFF0D0E10)
                    ) {
                        Spacer(Modifier.height(20.dp))
                        WideNavItem(Tab.LIBRARY, tab, "Library", Icons.Default.LibraryBooks, onTab)
                        WideNavItem(Tab.APPS, tab, "Apps", Icons.Default.Apps, onTab)
                        WideNavItem(Tab.SETTINGS, tab, "Settings", Icons.Default.Settings, onTab)
                    }
                }

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (load == null && error == null) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Acid)
                        }
                    } else if (load == null) {
                        Box(Modifier.fillMaxSize().padding(padding).padding(28.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Library couldn't open.", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(error.orEmpty(), color = TextSecondary)
                                Spacer(Modifier.height(18.dp))
                                Button(onClick = onRefresh) { Text("Try again") }
                            }
                        }
                    } else {
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
                                installStates,
                                padding,
                                wideLayout
                            )
                            Tab.APPS -> AppsScreen(
                                catalog.apps,
                                onOpen,
                                onLaunch,
                                onInstall,
                                installed,
                                installStates,
                                padding,
                                wideLayout
                            )
                            Tab.SETTINGS -> SettingsScreen(
                                hasToken,
                                load.warning,
                                onSaveToken,
                                onClearToken,
                                onRefresh,
                                padding,
                                wideLayout
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WideNavItem(
    tab: Tab,
    current: Tab,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onTab: (Tab) -> Unit
) {
    NavigationRailItem(
        selected = tab == current,
        onClick = { onTab(tab) },
        icon = { Icon(icon, label) },
        label = { Text(label, fontSize = 11.sp) },
        alwaysShowLabel = true,
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = Ink,
            indicatorColor = Acid,
            unselectedIconColor = TextSecondary,
            selectedTextColor = TextPrimary,
            unselectedTextColor = TextSecondary
        )
    )
}
'''
rep(old_main, new_main, 'MainShell')

rep('''    installed: Map<String, InstalledState>,
    installStates: Map<String, InstallState>
) {
    var query by remember { mutableStateOf("") }''', '''    installed: Map<String, InstalledState>,
    installStates: Map<String, InstallState>,
    padding: PaddingValues,
    wideLayout: Boolean
) {
    var query by rememberSaveable { mutableStateOf("") }''', 'LibraryScreen signature')
rep('''    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 54.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {''', '''    LazyVerticalGrid(
        columns = if (wideLayout) GridCells.Adaptive(340.dp) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = if (wideLayout) 32.dp else 20.dp,
            end = if (wideLayout) 32.dp else 20.dp,
            top = if (wideLayout) 32.dp else 54.dp,
            bottom = 32.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {''', 'Library grid')
rep('''        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Library",''', '''        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Library",''', 'Library title span')
rep('''        item {
            OutlinedTextField(
                value = query,''', '''        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = query,''', 'search span')
rep('''                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),''', '''                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),''', 'search width')
rep('''        warning?.let { item { Notice(it) } }
        if (visible.isEmpty()) {
            item {''', '''        warning?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.widthIn(max = 960.dp).fillMaxWidth()) { Notice(message) }
            }
        }
        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {''', 'warning/empty spans')

count = s.count('        items(visible, key = { it.id }) { app ->')
if count != 2:
    raise SystemExit(f'grid items: expected 2 occurrences, found {count}')
s = s.replace('        items(visible, key = { it.id }) { app ->', '        gridItems(visible, key = { it.id }) { app ->')

rep('''    installStates: Map<String, InstallState>,
    padding: PaddingValues
) {
    var updatesOnly by remember { mutableStateOf(false) }''', '''    installStates: Map<String, InstallState>,
    padding: PaddingValues,
    wideLayout: Boolean
) {
    var updatesOnly by rememberSaveable { mutableStateOf(false) }''', 'Apps signature')
rep('''    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Apps",''', '''    LazyVerticalGrid(
        columns = if (wideLayout) GridCells.Adaptive(340.dp) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(if (wideLayout) 32.dp else 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(8.dp))
            Text("Apps",''', 'Apps grid')
rep('''        if (visible.isEmpty()) {
            item {
                EmptyCard(
                    if (updatesOnly) "All current" else "No apps yet",''', '''        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyCard(
                    if (updatesOnly) "All current" else "No apps yet",''', 'Apps empty span')

rep('''    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 82.dp, bottom = 70.dp),''', '''    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 840.dp
        val sidePadding = if (wideLayout) {
            ((maxWidth - 1040.dp) / 2f).coerceAtLeast(32.dp)
        } else {
            20.dp
        }
        LazyColumn(
            contentPadding = PaddingValues(start = sidePadding, end = sidePadding, top = 82.dp, bottom = 70.dp),''', 'AppDetail constraints')
rep('''        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp),''', '''        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = (sidePadding - 8.dp).coerceAtLeast(12.dp), top = 12.dp),''', 'AppDetail back')

old_settings = '''private fun SettingsScreen(
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
    ) {'''
new_settings = '''private fun SettingsScreen(
    hasToken: Boolean,
    warning: String?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    padding: PaddingValues,
    wideLayout: Boolean
) {
    var token by remember { mutableStateOf("") }
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val sidePadding = if (wideLayout) {
            ((maxWidth - 760.dp) / 2f).coerceAtLeast(32.dp)
        } else {
            20.dp
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = sidePadding, end = sidePadding, top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {'''
rep(old_settings, new_settings, 'Settings opening')
needle = '''        item {
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
private fun Notice'''
replacement = '''        item {
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
}

@Composable
private fun Notice'''
rep(needle, replacement, 'Settings closing')

p.write_text(s)

manifest = Path('app/src/main/AndroidManifest.xml')
m = manifest.read_text()
old_activity = '''        <activity
            android:name=".MainActivity"
            android:exported="true">'''
new_activity = '''        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:resizeableActivity="true"
            android:windowSoftInputMode="adjustResize">'''
if m.count(old_activity) != 1:
    raise SystemExit(f'manifest activity: expected 1 occurrence, found {m.count(old_activity)}')
manifest.write_text(m.replace(old_activity, new_activity))

gradle = Path('gradle.properties')
g = gradle.read_text()
if g.count('LIBRARY_VERSION=1.0.7') != 1:
    raise SystemExit('expected LIBRARY_VERSION=1.0.7 exactly once')
gradle.write_text(g.replace('LIBRARY_VERSION=1.0.7', 'LIBRARY_VERSION=1.0.8'))

print('responsive UX applied; version bumped to 1.0.8')
