package me.naptila.neteaseios.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.naptila.neteaseios.bridge.GameSpec
import me.naptila.neteaseios.bridge.GameTarget
import me.naptila.neteaseios.bridge.InstalledGame
import me.naptila.neteaseios.bridge.SeedTransportMode
import me.naptila.neteaseios.ui.theme.NeteaseiosTheme
import me.naptila.neteaseios.ui.theme.StatusErrorContainerDark
import me.naptila.neteaseios.ui.theme.StatusErrorContainerLight
import me.naptila.neteaseios.ui.theme.StatusErrorDark
import me.naptila.neteaseios.ui.theme.StatusErrorLight
import me.naptila.neteaseios.ui.theme.StatusSuccessContainerDark
import me.naptila.neteaseios.ui.theme.StatusSuccessContainerLight
import me.naptila.neteaseios.ui.theme.StatusSuccessDark
import me.naptila.neteaseios.ui.theme.StatusSuccessLight
import me.naptila.neteaseios.ui.theme.StatusWarningContainerDark
import me.naptila.neteaseios.ui.theme.StatusWarningContainerLight
import me.naptila.neteaseios.ui.theme.StatusWarningDark
import me.naptila.neteaseios.ui.theme.StatusWarningLight

enum class StatusTone {
    Neutral,
    Success,
    Warning,
    Error
}

data class BridgeUiState(
    val currentGame: InstalledGame? = null,
    val installedApps: List<InstalledGame> = emptyList(),
    val loadingApps: Boolean = false,
    val recentPackages: List<String> = emptyList(),
    val confirmedPackages: Set<String> = emptySet(),
    val detectedGameId: String? = null,
    val busy: Boolean = false,
    val statusMessage: String = "请先选择目标网易游戏。",
    val statusTone: StatusTone = StatusTone.Neutral,
    val submittedCurrent: Boolean = false,
    val unconfirmedCurrent: Boolean = false,
    val transportMode: SeedTransportMode = SeedTransportMode.STANDARD,
    val nonEmptyToken: Boolean = false,
    val safAuthorized: Boolean = false,
    val diagnosticEvents: List<String> = emptyList()
) {
    val effectiveGameId: String? get() = currentGame?.spec?.gameId ?: detectedGameId
    val gameDisplayName: String get() = currentGame?.spec?.displayName ?: "目标游戏"
}

@Composable
fun BridgeScreen(
    state: BridgeUiState,
    onOpenGame: () -> Unit,
    onSelectInstalledGame: (InstalledGame) -> Unit,
    onRefreshApps: () -> Unit,
    onSelectTransportMode: (SeedTransportMode) -> Unit,
    onApplyNonEmptyToken: (Boolean) -> Unit,
    onAuthorizeSafDirectory: () -> Unit,
    onPrepareIosLogin: () -> Unit,
    onRefreshDiagnosticLog: () -> Unit,
    onClearDiagnosticLog: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker) {
        LaunchedEffect(Unit) { onRefreshApps() }
        InstalledAppPickerDialog(
            currentGame = state.currentGame,
            installedApps = state.installedApps,
            recentPackages = state.recentPackages,
            confirmedPackages = state.confirmedPackages,
            loading = state.loadingApps,
            onDismiss = { showAppPicker = false },
            onRefresh = onRefreshApps,
            onSelectApp = { app ->
                onSelectInstalledGame(app)
                showAppPicker = false
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = scheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AmbientBackdrop(dark = dark)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HeroHeaderCard(
                    state = state,
                    dark = dark,
                    onShowPicker = { showAppPicker = true },
                    onOpenGame = onOpenGame
                )

                StatusFeedbackBanner(
                    busy = state.busy,
                    message = state.statusMessage,
                    tone = state.statusTone,
                    dark = dark
                )

                PrepareIosCard(
                    state = state,
                    dark = dark,
                    onSelectTransportMode = onSelectTransportMode,
                    onAuthorizeSafDirectory = onAuthorizeSafDirectory,
                    onPrepareIosLogin = onPrepareIosLogin
                )
                LoginCompatibilityCard(
                    state = state,
                    onApplyNonEmptyToken = onApplyNonEmptyToken
                )
                DiagnosticLogCard(
                    state = state,
                    onRefresh = onRefreshDiagnosticLog,
                    onClear = onClearDiagnosticLog
                )
            }
        }
    }
}

@Composable
private fun AmbientBackdrop(dark: Boolean) {
    val topGlow = if (dark) Color(0xFF1A2F5A) else Color(0xFFDCE8FF)
    val goldGlow = if (dark) Color(0x33F6C36B) else Color(0x38F59E0B)
    val baseColor = MaterialTheme.colorScheme.background

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(topGlow.copy(alpha = if (dark) 0.55f else 0.7f), baseColor),
                startY = 0f,
                endY = size.height * 0.42f
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(goldGlow, Color.Transparent),
                center = Offset(size.width * 0.88f, size.height * 0.08f),
                radius = size.minDimension * 0.45f
            ),
            radius = size.minDimension * 0.45f,
            center = Offset(size.width * 0.88f, size.height * 0.08f)
        )
    }
}

@Composable
private fun HeroHeaderCard(
    state: BridgeUiState,
    dark: Boolean,
    onShowPicker: () -> Unit,
    onOpenGame: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val gradientColors = if (dark) {
        listOf(Color(0xFF182949), Color(0xFF131F36))
    } else {
        listOf(Color(0xFFFFFFFF), Color(0xFFF5F8FF))
    }
    val selected = state.currentGame

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dark) 0.dp else 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradientColors))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = scheme.tertiaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = AppIcons.IosBridgeLogo,
                            contentDescription = null,
                            tint = scheme.tertiary,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "网易游戏 iOS 登录",
                        style = MaterialTheme.typography.headlineSmall,
                        color = scheme.onSurface
                    )
                    Text(
                        text = "在安卓端登录 iOS 角色",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderLinkChip(
                    icon = AppIcons.GitHub,
                    label = "GitHub",
                    accentColor = scheme.primary,
                    dark = dark,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/Naptila/netease-ios") }
                    }
                )
                HeaderLinkChip(
                    icon = AppIcons.Bilibili,
                    label = "BiliBili",
                    accentColor = if (dark) Color(0xFFFF85AD) else Color(0xFFE0487B),
                    dark = dark,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        runCatching { uriHandler.openUri("https://space.bilibili.com/330170655") }
                    }
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = scheme.surfaceVariant.copy(alpha = if (dark) 0.55f else 0.7f),
                border = BorderStroke(
                    width = if (selected == null) 1.4.dp else 1.dp,
                    color = if (selected == null) scheme.primary.copy(alpha = 0.6f) else scheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = !state.busy, onClick = onShowPicker)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (selected != null) {
                        AsyncGameAppAvatar(
                            packageName = selected.spec.packageName,
                            cachedIcon = selected.icon,
                            size = 42
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = selected.spec.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                val gameCode = state.effectiveGameId
                                if (!gameCode.isNullOrBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = scheme.primaryContainer
                                    ) {
                                        Text(
                                            text = gameCode,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = scheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            val sub = buildString {
                                append(selected.spec.packageName)
                                if (selected.versionName.isNotBlank()) {
                                    append(" · v")
                                    append(selected.versionName)
                                }
                            }
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = scheme.primaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = AppIcons.Gamepad,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "点击选择目标游戏",
                                style = MaterialTheme.typography.titleSmall,
                                color = scheme.primary
                            )
                            Text(
                                text = "从本机已安装应用中选择",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (selected == null) "选择" else "更换",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary
                        )
                        Icon(
                            imageVector = AppIcons.ChevronDown,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (selected != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = if (selected.installed) "已安装" else "已卸载",
                        tone = if (selected.installed) StatusTone.Success else StatusTone.Error,
                        dark = dark,
                        modifier = Modifier.weight(1f)
                    )

                    val (modeLabel, modeTone) = when {
                        state.submittedCurrent -> "上次已确认" to StatusTone.Success
                        state.unconfirmedCurrent -> "已发送 · 待核对" to StatusTone.Warning
                        state.transportMode == SeedTransportMode.SAF_MEDIA -> {
                            if (state.safAuthorized) "目录已授权" to StatusTone.Success
                            else "目录待授权" to StatusTone.Warning
                        }
                        else -> "标准缓存" to StatusTone.Neutral
                    }
                    StatusPill(
                        text = modeLabel,
                        tone = modeTone,
                        dark = dark,
                        modifier = Modifier.weight(1f)
                    )

                    FilledTonalButton(
                        onClick = onOpenGame,
                        enabled = !state.busy && selected.installed,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.OpenExternal,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "打开游戏",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AsyncGameAppAvatar(
    packageName: String,
    cachedIcon: ImageBitmap?,
    size: Int
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var icon by remember(packageName) { mutableStateOf(cachedIcon) }
    LaunchedEffect(packageName, cachedIcon) {
        icon = cachedIcon ?: withContext(Dispatchers.IO) {
            GameTarget.loadAppIcon(context, packageName)
        }
    }

    val bmp = icon
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(11.dp))
        )
    } else {
        Surface(
            shape = RoundedCornerShape(11.dp),
            color = scheme.primaryContainer,
            modifier = Modifier.size(size.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.Gamepad,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size((size * 0.54f).dp)
                )
            }
        }
    }
}

@Composable
private fun InstalledAppPickerDialog(
    currentGame: InstalledGame?,
    installedApps: List<InstalledGame>,
    recentPackages: List<String>,
    confirmedPackages: Set<String>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelectApp: (InstalledGame) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }

    val filteredApps = remember(installedApps, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            installedApps
        } else {
            installedApps.filter {
                it.spec.displayName.contains(trimmed, ignoreCase = true) ||
                    it.spec.packageName.contains(trimmed, ignoreCase = true)
            }
        }
    }

    val recentApps = remember(filteredApps, recentPackages) {
        val matches = filteredApps.associateBy { it.spec.packageName }
        recentPackages.mapNotNull { matches[it] }
    }
    val remainingApps = remember(filteredApps, recentPackages) {
        filteredApps.filterNot { it.spec.packageName in recentPackages }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = scheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "选择网易游戏",
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface
                        )
                        Text(
                            text = "仅显示本机已安装的 com.netease.* 应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = AppIcons.Refresh,
                                contentDescription = "刷新应用列表",
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用名称或包名…") },
                    leadingIcon = {
                        Icon(
                            imageVector = AppIcons.Search,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (filteredApps.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (loading) "正在读取网易游戏…" else "没有找到匹配的网易游戏。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (recentApps.isNotEmpty()) {
                            item { Text("最近使用", style = MaterialTheme.typography.labelMedium) }
                            items(recentApps, key = { it.spec.packageName }) { app ->
                                InstalledAppRow(app, currentGame, app.spec.packageName in confirmedPackages, onSelectApp)
                            }
                            if (remainingApps.isNotEmpty()) {
                                item { Text("其他已安装应用", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        items(remainingApps, key = { it.spec.packageName }) { app ->
                            InstalledAppRow(app, currentGame, false, onSelectApp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppRow(
    item: InstalledGame,
    currentGame: InstalledGame?,
    confirmed: Boolean,
    onSelectApp: (InstalledGame) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val selected = item.spec.packageName == currentGame?.spec?.packageName
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.7f)
        else scheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelectApp(item) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AsyncGameAppAvatar(
                packageName = item.spec.packageName,
                cachedIcon = item.icon,
                size = 38
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.spec.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (confirmed) {
                    Text("上次准备已确认", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                }
                Text(
                    text = item.spec.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SelectionIndicator(selected = selected)
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    tone: StatusTone,
    dark: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        StatusTone.Success -> if (dark) StatusSuccessContainerDark to StatusSuccessDark else StatusSuccessContainerLight to StatusSuccessLight
        StatusTone.Warning -> if (dark) StatusWarningContainerDark to StatusWarningDark else StatusWarningContainerLight to StatusWarningLight
        StatusTone.Error -> if (dark) StatusErrorContainerDark to StatusErrorDark else StatusErrorContainerLight to StatusErrorLight
        StatusTone.Neutral -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatusFeedbackBanner(
    busy: Boolean,
    message: String,
    tone: StatusTone,
    dark: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val targetBg = when {
        busy -> scheme.primaryContainer
        tone == StatusTone.Success -> if (dark) StatusSuccessContainerDark else StatusSuccessContainerLight
        tone == StatusTone.Warning -> if (dark) StatusWarningContainerDark else StatusWarningContainerLight
        tone == StatusTone.Error -> if (dark) StatusErrorContainerDark else StatusErrorContainerLight
        else -> scheme.surfaceVariant.copy(alpha = if (dark) 0.75f else 0.85f)
    }
    val targetFg = when {
        busy -> scheme.onPrimaryContainer
        tone == StatusTone.Success -> if (dark) StatusSuccessDark else StatusSuccessLight
        tone == StatusTone.Warning -> if (dark) StatusWarningDark else StatusWarningLight
        tone == StatusTone.Error -> if (dark) StatusErrorDark else StatusErrorLight
        else -> scheme.onSurfaceVariant
    }

    val containerColor by animateColorAsState(targetBg, tween(250), label = "statusBg")
    val contentColor by animateColorAsState(targetFg, tween(250), label = "statusFg")

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.4.dp,
                    color = contentColor
                )
            } else {
                val icon = when (tone) {
                    StatusTone.Success -> AppIcons.Check
                    StatusTone.Warning, StatusTone.Error -> AppIcons.Alert
                    StatusTone.Neutral -> AppIcons.Info
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedContent(
                targetState = message,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "statusText",
                modifier = Modifier.weight(1f)
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun PrepareIosCard(
    state: BridgeUiState,
    dark: Boolean,
    onSelectTransportMode: (SeedTransportMode) -> Unit,
    onAuthorizeSafDirectory: () -> Unit,
    onPrepareIosLogin: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val hasGame = state.currentGame != null
    val warnBg = if (dark) StatusWarningContainerDark else StatusWarningContainerLight
    val warnFg = if (dark) StatusWarningDark else StatusWarningLight

    val borderColor by animateColorAsState(
        targetValue = when {
            state.submittedCurrent -> (if (dark) StatusSuccessDark else StatusSuccessLight).copy(alpha = 0.45f)
            hasGame -> scheme.primary.copy(alpha = 0.5f)
            else -> scheme.outlineVariant
        },
        animationSpec = tween(220),
        label = "cardBorder"
    )

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(if (hasGame) 1.3.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dark) 0.dp else 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "准备 iOS 登录",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface
                )
                Text(
                    text = "按当前方式准备 iOS 登录数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }

            var showOptions by remember(state.currentGame?.spec?.packageName) { mutableStateOf(false) }
            val modeName = if (state.transportMode == SeedTransportMode.STANDARD) "标准缓存" else "共享目录"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前方式：$modeName", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { showOptions = !showOptions }, enabled = !state.busy && hasGame) {
                    Text(if (showOptions) "收起" else "更改")
                }
            }
            if (showOptions) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransportModeOptionItem(
                        title = "标准缓存（其他网易游戏默认）",
                        description = "适用于光·遇等大多数官服游戏",
                        selected = state.transportMode == SeedTransportMode.STANDARD,
                        enabled = !state.busy,
                        onClick = { onSelectTransportMode(SeedTransportMode.STANDARD) }
                    )

                    TransportModeOptionItem(
                        title = "共享目录（阴阳师默认）",
                        description = "授权 Android/media 写入，适配无缓存接口游戏",
                        selected = state.transportMode == SeedTransportMode.SAF_MEDIA,
                        enabled = !state.busy,
                        onClick = { onSelectTransportMode(SeedTransportMode.SAF_MEDIA) }
                    )

                }
            }

            // SAF authorization is visible even when advanced mode choices are collapsed.
            if (state.transportMode == SeedTransportMode.SAF_MEDIA) {
                        Text("目录授权", style = MaterialTheme.typography.titleSmall)
                        Text("共享目录需要先授权文件夹", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        val authToneFg = if (state.safAuthorized) {
                            if (dark) StatusSuccessDark else StatusSuccessLight
                        } else {
                            scheme.onSurfaceVariant
                        }
                        Surface(
                            shape = RoundedCornerShape(13.dp),
                            color = scheme.surfaceVariant.copy(alpha = if (dark) 0.5f else 0.65f),
                            border = BorderStroke(1.dp, scheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = AppIcons.Folder,
                                    contentDescription = null,
                                    tint = authToneFg,
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    text = if (state.safAuthorized) "已授权 Android/media" else "待授权 Android/media",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = authToneFg,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = onAuthorizeSafDirectory,
                                    enabled = !state.busy,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (state.safAuthorized) "更换" else "授权",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }

            Button(
                onClick = onPrepareIosLogin,
                enabled = !state.busy && state.currentGame?.installed == true &&
                    (state.transportMode != SeedTransportMode.SAF_MEDIA || state.safAuthorized),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                contentPadding = PaddingValues(vertical = 15.dp, horizontal = 20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = AppIcons.UserScan,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "一键准备 iOS 登录",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Surface(
                shape = RoundedCornerShape(13.dp),
                color = warnBg,
                border = BorderStroke(1.dp, warnFg.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = AppIcons.Alert,
                        contentDescription = null,
                        tint = warnFg,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(17.dp)
                    )
                    Text(
                        text = "如果游戏的登录弹窗已打开，请先关闭再重开。以游戏实际显示的「重新登录」或「切换账号」为准，勿直接点旧缓存记录。",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = warnFg
                    )
                }
            }

            // Restoring Android roles is unrelated to the iOS compatibility retry;
            // avoid presenting a misleading account-removal action inside this card.
        }
    }
}

/**
 * Non-empty placeholder tokens are opt-in and have no effect on previously imported sessions.
 * Keep the switch local to this dialog until the user explicitly confirms re-preparation.
 */
@Composable
private fun LoginCompatibilityCard(
    state: BridgeUiState,
    onApplyNonEmptyToken: (Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val target = state.currentGame
    val enabled = target?.installed == true && !state.busy
    var showSettings by remember { mutableStateOf(false) }
    var pendingNonEmptyToken by remember(target?.spec?.packageName, showSettings) {
        mutableStateOf(state.nonEmptyToken)
    }
    val hasPendingChange = pendingNonEmptyToken != state.nonEmptyToken

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = AppIcons.SwitchAccount,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("登录兼容性设置", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (state.nonEmptyToken) "iOS 兼容重试：已开启" else "iOS 兼容重试：默认关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = { showSettings = true },
                enabled = enabled
            ) {
                Text("打开设置")
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("登录兼容性设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "当前游戏：「${state.gameDisplayName}」",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "首次使用请保持关闭，先执行默认 iOS 登录准备并在游戏中重新登录核对角色。若仍进入安卓角色，再开启兼容模式，重新准备 iOS 登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("非空占位凭证", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "开启后使用非空占位 token 重新生成 iOS 准备数据，尝试兼容部分游戏版本。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = pendingNonEmptyToken,
                            onCheckedChange = { pendingNonEmptyToken = it },
                            enabled = enabled
                        )
                    }
                    Text(
                        "点击「应用并重试」才会保存选项，立即重新生成 iOS 准备文件并调用游戏 SDK。这不是切回安卓角色的功能；占位 token 也不是有效登录凭证。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                    Text(
                        "注意：兼容重试不保证进入 iOS 角色。部分 SDK 可能拒绝该格式；请确保能通过游戏官方入口重新登录原账号。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val selected = pendingNonEmptyToken
                        showSettings = false
                        onApplyNonEmptyToken(selected)
                    },
                    enabled = enabled && hasPendingChange
                ) {
                    Text(
                        when {
                            !hasPendingChange -> "设置未更改"
                            pendingNonEmptyToken -> "开启兼容 · 重试 iOS 准备"
                            else -> "关闭兼容 · 重试 iOS 准备"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("取消") }
            }
        )
    }
}

/**
 * Locally stored, limited non-secret diagnostic events.
 * Displaying the log never invokes the game's login SDK.
 */
@Composable
private fun DiagnosticLogCard(
    state: BridgeUiState,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var showLog by remember(state.currentGame?.spec?.packageName) { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("诊断日志", style = MaterialTheme.typography.titleSmall)
                Text(
                    "检查目录写入、SDK 调用和回调结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = { onRefresh(); showLog = true },
                enabled = state.currentGame != null
            ) {
                Text("查看")
            }
        }
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("诊断日志 · ${state.gameDisplayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "仅保存最近 60 条操作阶段，不含账号、UID、密码、token 和原始 SDK 回调。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                    Surface(
                        color = scheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 330.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                if (state.diagnosticEvents.isEmpty()) {
                                    Text("暂无日志。执行一次登录准备后再查看。", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    state.diagnosticEvents.forEach { entry ->
                                        Text(
                                            text = entry,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = scheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = onClear,
                        enabled = state.diagnosticEvents.isNotEmpty()
                    ) {
                        Text("清空本游戏日志")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(
                            AnnotatedString(state.diagnosticEvents.joinToString("\n"))
                        )
                    },
                    enabled = state.diagnosticEvents.isNotEmpty()
                ) {
                    Text("复制日志")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLog = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (selected) {
        Surface(
            shape = CircleShape,
            color = scheme.primary,
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.5.dp, scheme.outline),
                modifier = Modifier.fillMaxSize()
            ) {}
        }
    }
}

@Composable
private fun TransportModeOptionItem(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.6f)
        else scheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(
            width = if (selected) 1.4.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            SelectionIndicator(selected = selected)
        }
    }
}

@Composable
private fun HeaderLinkChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accentColor: Color,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = if (dark) 0.14f else 0.09f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = if (dark) 0.35f else 0.25f)),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accentColor,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = AppIcons.OpenExternal,
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.8f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BridgeScreenPreview() {
    NeteaseiosTheme {
        BridgeScreen(
            state = BridgeUiState(
                currentGame = InstalledGame(
                    spec = GameSpec(packageName = "com.netease.sky", displayName = "光·遇", gameId = "ma75"),
                    versionName = "0.16.8",
                    installed = true
                ),
                statusMessage = "先在「光·遇」登录目标账号，再点下方按钮一键准备。",
                statusTone = StatusTone.Neutral
            ),
            onOpenGame = {},
            onSelectInstalledGame = {},
            onRefreshApps = {},
            onSelectTransportMode = {},
            onApplyNonEmptyToken = {},
            onAuthorizeSafDirectory = {},
            onPrepareIosLogin = {},
            onRefreshDiagnosticLog = {},
            onClearDiagnosticLog = {}
        )
    }
}
