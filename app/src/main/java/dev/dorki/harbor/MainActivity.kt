package dev.dorki.harbor

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dorki.harbor.ui.screens.FilesScreen
import dev.dorki.harbor.ui.screens.HomeScreen
import dev.dorki.harbor.ui.screens.NasEditor
import dev.dorki.harbor.ui.screens.ServersScreen
import dev.dorki.harbor.ui.screens.SettingsScreen
import dev.dorki.harbor.ui.components.LocalBarInset
import dev.dorki.harbor.ui.components.Tab
import dev.dorki.harbor.ui.components.TabBar
import dev.dorki.harbor.ui.components.TabBarHeight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import dev.dorki.harbor.ui.screens.WebCache
import dev.dorki.harbor.ui.screens.WebScreen
import dev.dorki.harbor.ui.theme.HarborTheme
import dev.dorki.harbor.ui.theme.Palette

class MainActivity : ComponentActivity() {
    private val webCache = WebCache()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val vm: HarborViewModel = viewModel()
            val appearance by vm.appearance.collectAsStateWithLifecycle()
            HarborTheme(appearance) { HarborApp(webCache, vm) }
        }
    }

    override fun onDestroy() {
        webCache.destroyAll()
        super.onDestroy()
    }
}

@Composable
fun HarborApp(webCache: WebCache, vm: HarborViewModel = viewModel()) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val tiles by vm.tiles.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.store.activeId.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // null = no editor; "" = adding a NAS; otherwise the id being edited.
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val haze = remember { HazeState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    val tile = tiles.firstOrNull { it.id == openId }
    val webState = remember { MutableTransitionState(false) }
    webState.targetState = tile != null
    // Once an app fully covers the home screen, drop the home screen from composition: its
    // scrollable grid underneath would otherwise steal vertical drags from the WebView.
    var share by remember { mutableStateOf<Share?>(null) }
    val filesState = remember { MutableTransitionState(false) }
    filesState.targetState = share != null
    val webCovering = (webState.currentState && webState.isIdle) || (filesState.currentState && filesState.isIdle)
    val gridState = rememberLazyGridState()
    LaunchedEffect(activeId) { gridState.scrollToItem(0) }

    val firstRun = profiles.isEmpty()
    val barInset = TabBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(Palette.Black)) {
        if (firstRun || editor != null) {
            BackHandler(enabled = editor != null && !firstRun) { editor = null }
            NasEditor(vm, id = editor?.takeIf { it.isNotEmpty() }, firstRun = firstRun, onClose = { editor = null })
        } else {
            CompositionLocalProvider(LocalBarInset provides barInset) {
                Box(Modifier.fillMaxSize().haze(haze)) {
                    AnimatedContent(tab, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "tab") { t ->
                        when (t) {
                            Tab.HOME -> if (!webCovering) HomeScreen(
                                vm, onOpen = { openId = it.id }, onSettings = { editor = activeId },
                                onSwitch = { switching = true }, gridState = gridState, onOpenShare = { share = it },
                            )
                            Tab.SERVERS -> ServersScreen(
                                vm,
                                onOpen = { c -> vm.switchTo(c.id); tab = Tab.HOME },
                                onEdit = { editor = it },
                                onAdd = { editor = "" },
                            )
                            Tab.SETTINGS -> SettingsScreen(vm)
                        }
                    }
                }
            }
            if (tab != Tab.HOME) BackHandler { tab = Tab.HOME }
        }

        AnimatedVisibility(
            visibleState = webState,
            enter = slideInVertically(tween(280)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(180)),
        ) {
            // Hold on to the last tile so the exit animation has something to draw.
            val shown = remember { mutableStateOf(tile) }
            if (tile != null) shown.value = tile
            shown.value?.let { t ->
                WebScreen(vm, t, t.url, conn, webCache, onClose = { openId = null })
            }
        }

        AnimatedVisibility(
            visibleState = filesState,
            enter = slideInVertically(tween(280)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(180)),
        ) {
            val shown = remember { mutableStateOf(share) }
            if (share != null) shown.value = share
            shown.value?.let { sh -> FilesScreen(vm, sh, onClose = { share = null }) }
        }

        if (!firstRun && editor == null && !webCovering && !webState.targetState && !filesState.targetState) {
            TabBar(tab, { tab = it }, haze, Modifier.align(Alignment.BottomCenter))
        }

        SnackbarHost(
            snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).padding(bottom = if (editor == null && !firstRun) TabBarHeight else 0.dp),
        ) {
            Snackbar(it, containerColor = Palette.Surface3, contentColor = Palette.Text, shape = Palette.shape(14.dp))
        }
    }

    if (switching) NasSwitcher(vm, onDismiss = { switching = false })
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NasSwitcher(vm: HarborViewModel, onDismiss: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.store.activeId.collectAsStateWithLifecycle()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = Palette.Surface,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
    ) {
        dev.dorki.harbor.ui.components.GlassBehind()
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            androidx.compose.material3.Text("Switch NAS", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            profiles.forEach { c ->
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().clip(Palette.shape(14.dp)).clickable { vm.switchTo(c.id); onDismiss() }.padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dev.dorki.harbor.ui.components.Dot(if (c.id == active) Palette.Accent else Palette.Faint, size = 10.dp)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 14.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text(c.title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                        androidx.compose.material3.Text(c.kind.label, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                }
            }
        }
    }
}
