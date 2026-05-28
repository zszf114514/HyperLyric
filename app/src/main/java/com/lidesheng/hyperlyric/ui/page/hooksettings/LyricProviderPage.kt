package com.lidesheng.hyperlyric.ui.page.hooksettings

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.edit
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.ui.navigation.LocalNavigator
import com.lidesheng.hyperlyric.ui.component.SuperSwitchPreference
import com.lidesheng.hyperlyric.ui.component.ProComponent
import com.lidesheng.hyperlyric.ui.component.TagComponent
import com.lidesheng.hyperlyric.utils.LyricProviderManager
import com.lidesheng.hyperlyric.utils.ModuleCategory
import com.lidesheng.hyperlyric.utils.ModuleTag
import com.lidesheng.hyperlyric.utils.ProviderUiState
import com.lidesheng.hyperlyric.ui.utils.BlurredBar
import com.lidesheng.hyperlyric.ui.utils.pageScrollModifiers
import com.lidesheng.hyperlyric.ui.utils.rememberBlurBackdrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import com.lidesheng.hyperlyric.ui.component.TextInputDialog
import com.lidesheng.hyperlyric.ui.component.SimpleDialog
import com.lidesheng.hyperlyric.lyric.ConfigRepository
import com.lidesheng.hyperlyric.lyric.DynamicLyricData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

@Composable
fun LyricProviderPage() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val providerReleaseHome = stringResource(R.string.provider_release_home)
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    val providerUiStateFlow = remember { MutableStateFlow(ProviderUiState()) }
    val providerUiState = providerUiStateFlow.collectAsState()
    val pullToRefreshState = rememberPullToRefreshState()
    val snackbarHostState = remember { SnackbarHostState() }

    val addedSet by ConfigRepository.hookAddedState.collectAsState()
    val activeSet by ConfigRepository.hookWhitelistState.collectAsState()
    val manualWhitelist = remember(addedSet, providerUiState.value.modules) {
        val providerPkgs = providerUiState.value.modules.map { it.packageInfo.packageName }.toSet()
        val coveredTargetPkgs = providerUiState.value.modules.flatMap { 
            LyricProviderManager.providerToTargetMap[it.packageInfo.packageName] ?: emptyList() 
        }.toSet()
        // 过滤掉：1. 插件自身 2. 已被当前插件覆盖的目标 App
        addedSet.filter { it !in providerPkgs && it !in coveredTargetPkgs }.toList()
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var tempInput by remember { mutableStateOf("") }
    var packageToDelete by remember { mutableStateOf("") }

    val msgAppExists = stringResource(id = R.string.toast_app_exists)
    val msgPkgEmpty = stringResource(id = R.string.toast_pkg_empty)

    val providerPrefs = remember { context.getSharedPreferences("provider_settings", Context.MODE_PRIVATE) }
    val providerStates = remember { mutableStateMapOf<String, Boolean>() }
    var hasLoadedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { 
        LyricProviderManager.loadProviders(context, providerUiStateFlow)
        ConfigRepository.initWhitelist(context)
    }

    // 处理插件开关的持久化与“初见开启”逻辑
    LaunchedEffect(providerUiState.value.modules) {
        val modules = providerUiState.value.modules
        if (modules.isNotEmpty()) {
            modules.forEach { module ->
                val pkg = module.packageInfo.packageName
                if (!providerPrefs.contains(pkg)) {
                    // 第一次发现插件：默认开启
                    providerPrefs.edit { putBoolean(pkg, true) }
                    providerStates[pkg] = true
                    // 同步开启关联 App 的 Hook 状态
                    val targets = LyricProviderManager.providerToTargetMap[pkg] ?: listOf(pkg)
                    targets.forEach { target ->
                        ConfigRepository.toggleHookStatus(context, target, true)
                    }
                } else {
                    providerStates[pkg] = providerPrefs.getBoolean(pkg, true)
                }
            }
        }
    }

    LaunchedEffect(providerUiState.value.isLoading) {
        if (providerUiState.value.isLoading) {
            hasLoadedOnce = true
        }
        // 只有当确定执行过加载，且加载结束时，才执行清理
        if (!providerUiState.value.isLoading && hasLoadedOnce) {
            val providerPkgs = providerUiState.value.modules.map { it.packageInfo.packageName }.toSet()
            ConfigRepository.cleanupOrphanedPackages(context, providerPkgs)
        }
    }

    TextInputDialog(
        show = showAddDialog,
        title = stringResource(id = R.string.dialog_add_whitelist_title),
        initialValue = tempInput,
        label = stringResource(id = R.string.dialog_add_whitelist_hint),
        confirmText = stringResource(id = R.string.save),
        onDismiss = { showAddDialog = false },
        onConfirm = { input ->
            if (input.isNotBlank()) {
                val success = ConfigRepository.addPackageToHookList(context, input)
                if (success) showAddDialog = false
                else coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = msgAppExists,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                }
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = msgPkgEmpty,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                }
            }
        }
    )

    SimpleDialog(
        show = showDeleteDialog,
        title = stringResource(id = R.string.dialog_delete_whitelist_title),
        onDismiss = { showDeleteDialog = false },
        onConfirm = {
            ConfigRepository.removePackageFromHookPage(context, packageToDelete)
            showDeleteDialog = false
        }
    )

    val othersCategoryName = stringResource(id = R.string.category_others)
    val groupedModules = remember(providerUiState.value.modules) {
        LyricProviderManager.categorizeModules(providerUiState.value.modules, othersCategoryName)
    }

    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(id = R.string.title_lyric_provider),
                    scrollBehavior = topAppBarScrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(id = R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, providerReleaseHome.toUri())) } catch (_: Exception) {} }) {
                            Icon(painter = painterResource(id = R.drawable.ic_github), contentDescription = stringResource(id = R.string.github), tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(26.dp))
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { tempInput = ""; showAddDialog = true }
            ) {
                Icon(imageVector = MiuixIcons.Add, contentDescription = stringResource(id = R.string.add), tint = Color.White)
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            PullToRefresh(
                isRefreshing = providerUiState.value.isLoading,
                onRefresh = { coroutineScope.launch { LyricProviderManager.loadProviders(context, providerUiStateFlow) } },
                pullToRefreshState = pullToRefreshState,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
                refreshTexts = listOf(stringResource(id = R.string.refresh_pull_down), stringResource(id = R.string.refresh_release), stringResource(id = R.string.refreshing), stringResource(id = R.string.refresh_success)),
                modifier = Modifier.fillMaxSize()
            ) {
                val lazyListState = rememberLazyListState()
                val top = innerPadding.calculateTopPadding()
                val bottom = innerPadding.calculateBottomPadding()
                val contentPadding = remember(top, bottom) {
                    PaddingValues(top = top, start = 0.dp, end = 0.dp, bottom = bottom)
                }
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.pageScrollModifiers(
                        enableScrollEndHaptic = true,
                        showTopAppBar = false,
                        topAppBarScrollBehavior = topAppBarScrollBehavior
                    ),
                    contentPadding = contentPadding,
                ) {
                    providerSections(
                        uiState = providerUiState.value,
                        groupedModules = groupedModules,
                        expandedStates = expandedStates,
                        providerStates = providerStates,
                        onProviderToggle = { pkg, isChecked ->
                            providerStates[pkg] = isChecked
                            providerPrefs.edit { putBoolean(pkg, isChecked) }
                        },
                        manualWhitelist = manualWhitelist,
                        activeSet = activeSet,
                        onDeleteManual = {
                            packageToDelete = it
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }
}

private fun LazyListScope.providerSections(
    uiState: ProviderUiState,
    groupedModules: List<ModuleCategory>,
    expandedStates: MutableMap<String, Boolean>,
    providerStates: Map<String, Boolean>,
    onProviderToggle: (String, Boolean) -> Unit,
    manualWhitelist: List<String>,
    activeSet: Set<String>,
    onDeleteManual: (String) -> Unit
) {
    if (!uiState.isLoading && uiState.modules.isEmpty()) {
        item(key = "no_provider") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                ProComponent(
                    title = stringResource(id = R.string.title_no_provider), 
                    summary = stringResource(id = R.string.summary_no_provider)
                )
            }
        }
    } else {
        groupedModules.forEach { category ->
            if (category.name.isNotBlank()) {
                item(key = "header_${category.name}") {
                    SmallTitle(text = category.name, insideMargin = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp))
                }
            }
            items(category.items.size, key = { "provider_${category.items[it].packageInfo.packageName}" }) { index ->
                val context = LocalContext.current
                val module = category.items[index]
                val packageName = module.packageInfo.packageName
                val isExpanded = expandedStates[packageName] ?: false
                
                val isEnabled = providerStates[packageName] ?: true

                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .fillMaxWidth(),
                    onClick = { expandedStates[packageName] = !isExpanded }
                ) {
                    Column {
                        SuperSwitchPreference(
                            checked = isEnabled,
                            onCheckedChange = { isChecked ->
                                onProviderToggle(packageName, isChecked)
                                val targets = LyricProviderManager.providerToTargetMap[packageName] ?: listOf(packageName)
                                targets.forEach { target ->
                                    ConfigRepository.toggleHookStatus(context, target, isChecked)
                                }
                            },
                            title = module.label,
                            summary = stringResource(
                                id = R.string.format_version_author,
                                module.packageInfo.versionName ?: stringResource(id = R.string.unknown),
                                module.author ?: stringResource(id = R.string.unknown_author)
                            ),
                            onClick = { expandedStates[packageName] = !isExpanded },
                            startAction = {
                                val pm = LocalContext.current.packageManager
                                val appInfo = module.packageInfo.applicationInfo
                                val icon = remember(packageName) { appInfo?.loadIcon(pm) }
                                if (icon != null) {
                                    Box(modifier = Modifier.size(40.dp)) {
                                        androidx.compose.ui.viewinterop.AndroidView(
                                            factory = { context ->
                                                android.widget.ImageView(context).apply {
                                                    setImageDrawable(icon)
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            },
                            showIndication = false
                        )
                        AnimatedVisibility(visible = isExpanded) {
                            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                ProComponent(
                                    summary = module.description,
                                    insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 0.dp)
                                )
                                if (module.tags.isNotEmpty()) {
                                    ModuleTagsFlow(module.tags)
                                }
                            
                                val associatedPkgs = LyricProviderManager.providerToTargetMap[packageName]
                                if (!associatedPkgs.isNullOrEmpty()) {
                                    ProComponent(
                                        summary = associatedPkgs.joinToString("\n"),
                                        insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 0.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 追加手动添加的白名单卡片
    if (manualWhitelist.isNotEmpty()) {
        items(manualWhitelist.size, key = { "manual_${manualWhitelist[it]}" }) { index ->
            val context = LocalContext.current
            val packageName = manualWhitelist[index]
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth(),
                onClick = { onDeleteManual(packageName) }
            ) {
                SuperSwitchPreference(
                    checked = activeSet.contains(packageName),
                    onCheckedChange = { isChecked ->
                                ConfigRepository.toggleHookStatus(context, packageName, isChecked)
                    },
                    title = packageName,
                    onClick = { onDeleteManual(packageName) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleTagsFlow(tags: List<ModuleTag>) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        tags.forEach { tag ->
            val title = if (tag.titleRes != -1) stringResource(tag.titleRes) else tag.title.orEmpty()
            TagComponent(
                text = title,
                iconRes = tag.iconRes,
                imageVector = tag.imageVector,
                isRainbow = tag.isRainbow,
                modifier = Modifier.padding(end = 10.dp)
            )
        }
    }
}
