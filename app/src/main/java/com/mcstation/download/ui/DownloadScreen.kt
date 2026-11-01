package com.mcstation.download.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mcstation.download.data.mc.McDataBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcstation.download.data.model.McVersion

/**
 * 下载页：搜索框 + 可展开/收起的类型筛选（正式版/测试版/全部）+ 版本列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    viewModel: AppViewModel,
    onVersionClick: (McVersion) -> Unit,
    modifier: Modifier = Modifier,
    // 列表滚动状态由调用方持有：进详情页再返回时不会跳回顶部
    listState: LazyListState = rememberLazyListState(),
) {
    // 筛选按钮组展开/收起：状态提在 ViewModel（跨界面保留，切页不重置）
    val filterExpanded = viewModel.filterExpanded
    // 大版本筛选行显示/隐藏（由类型筛选行右侧的漏斗图标切换）
    val majorVisible = viewModel.majorVisible

    // 是否有托管中的游戏数据（备份完成即显示横幅）
    var backupInfo by remember { mutableStateOf<McDataBackup.BackupInfo?>(null) }
    var pasteBusy by remember { mutableStateOf(false) }
    // 粘贴前的确认弹窗 / "我的数据未被删除"的删除备份确认弹窗
    var showPasteConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 进入页面 & 下载任务变化时刷新（备份发生在下载开始前，任务变化即可触发刷新）
    LaunchedEffect(viewModel.downloadTasks.size) {
        backupInfo = withContext(Dispatchers.IO) { McDataBackup.backupInfo(context) }
    }

    // 搜索停顿后记进历史（不要每敲一个字都写盘）
    LaunchedEffect(viewModel.query) {
        val q = viewModel.query
        if (q.trim().length >= 2) {
            kotlinx.coroutines.delay(1200)
            viewModel.rememberQuery(q)
        }
    }

    // 把保管的数据粘贴回 Minecraft（装好新包后使用）
    val pasteData: () -> Unit = {
        if (!McDataBackup.hasStorageAccess(context)) {
            if (!McDataBackup.openAllFilesAccessSettings(context)) {
                android.widget.Toast
                    .makeText(context, "请先授予存储访问权限", android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        } else if (!pasteBusy) {
            pasteBusy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { McDataBackup.restore(context) }
                pasteBusy = false
                val msg = if (result.ok) "数据已粘贴到 Minecraft" else (result.error ?: "粘贴失败")
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // 搜索框 + 展开/收起按钮（紧凑高度，与预览一致）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(10.dp))
                    Box {
                        if (viewModel.query.isEmpty()) {
                            Text("搜索版本", color = MaterialTheme.colorScheme.outline, fontSize = 15.sp)
                        }
                        BasicTextField(
                            value = viewModel.query,
                            onValueChange = { viewModel.query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { viewModel.filterExpanded = !viewModel.filterExpanded }) {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (filterExpanded) "收起筛选" else "展开筛选",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.rotate(if (filterExpanded) 180f else 0f),
                )
            }
        }

        // 数据保管横幅（有备份时显示）：紧贴搜索栏下方
        backupInfo?.let {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "我们正在保管您的数据",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "如果你下载好了Minecraft，请点击“粘贴数据到应用”。" +
                        "如果打开Minecraft后发现你的数据未被删除，请点击“我的数据未被删除”\n" +
                        "如果粘贴后数据没有恢复，请在Minecraft设置里选择对应的文件存储位置并重启游戏",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                // 描边样式（不填充），避免与页面主色按钮抢视觉
                OutlinedButton(
                    onClick = { showPasteConfirm = true },
                    enabled = !pasteBusy,
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 18.dp,
                        vertical = 0.dp,
                    ),
                ) {
                    Text(if (pasteBusy) "正在粘贴…" else "粘贴数据到应用", fontSize = 13.sp)
                }
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 2.dp,
                        vertical = 0.dp,
                    ),
                ) {
                    Text(
                        text = "我的数据未被删除",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // 类型筛选 + 大版本筛选（带展开/收起动画）
        AnimatedVisibility(visible = filterExpanded) {
            // 关闭 FilterChip 的 48dp 最小触摸目标强制：
            // 否则 32dp 的视觉 chip 被居中放进 48dp 布局，两行之间会凭空多出 16dp 留白
            // （material3 1.3 用新开关 LocalMinimumInteractiveComponentSize，Dp.Unspecified = 不强制）
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            ) {
                Column {
                // 版本类型筛选行与搜索框之间空 8dp
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VersionFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = viewModel.filterMode == filter,
                                onClick = { viewModel.filterMode = filter },
                                label = { Text(filter.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 漏斗图标：切换大版本筛选行的显示/隐藏
                    IconButton(onClick = { viewModel.majorVisible = !viewModel.majorVisible }) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = if (majorVisible) "收起版本筛选" else "展开版本筛选",
                            tint = if (majorVisible) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                // 大版本筛选：600+ 个版本一路滑太久，先按版本段收窄
                AnimatedVisibility(visible = majorVisible) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = viewModel.majorFilter == null,
                            onClick = { viewModel.majorFilter = null },
                            label = { Text("不限") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                    items(viewModel.majorGroups, key = { it.first }) { (label, count) ->
                        FilterChip(
                            selected = viewModel.majorFilter == label,
                            onClick = {
                                viewModel.majorFilter = if (viewModel.majorFilter == label) null else label
                            },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
                }
                }
            }
        }

        // 最近搜索：搜索框空着时给快捷入口，省得每次重打版本号
        if (viewModel.query.isBlank() && viewModel.recentQueries.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(viewModel.recentQueries, key = { it }) { q ->
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                                .clickable { viewModel.query = q }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        ) {
                            Text(q, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(
                    onClick = { viewModel.clearRecentQueries() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("清除", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // 首次使用提示：版本很多，先教一次怎么找
        if (viewModel.showListHint && !viewModel.loading && viewModel.versions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 ${viewModel.versions.size} 个版本，用上面的大版本筛选或搜索框能更快找到",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.dismissListHint() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("知道了", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 筛选区与下方版本列表的间距（与两行 chip 之间的间距保持一致）
        Spacer(Modifier.height(8.dp))

        when {
            // ---------- 加载中 ----------
            viewModel.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(14.dp))
                        Text("正在获取版本列表...", color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
                    }
                }
            }
            // ---------- 加载失败（无数据可用）----------
            viewModel.loadError -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "获取失败，请检查网络或稍后再试",
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { viewModel.load(forceRefresh = true) },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
            // ---------- 正常列表 ----------
            viewModel.filtered.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配的版本", color = MaterialTheme.colorScheme.outline)
                }
            }
            else -> {
                // 全量显示：26.x 在前，1.x 旧版本紧随其后（2026-10-25 起不再需要开关）
                val filtered = viewModel.filtered
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(VersionRowStyle.itemSpacing),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.version }) { version ->
                            VersionItem(
                                version = version,
                                installed = viewModel.isVersionInstalled(version),
                                onClick = { onVersionClick(version) },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }

                    // 上方已经滑过 10 个以上版本时，右下角浮出"回到顶部"小圆钮
                    androidx.compose.animation.AnimatedVisibility(
                        visible = listState.firstVisibleItemIndex > 10,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 4.dp, bottom = 16.dp),
                        enter = fadeIn() + scaleIn(initialScale = 0.7f),
                        exit = fadeOut() + scaleOut(targetScale = 0.7f),
                    ) {
                        SmallFloatingActionButton(
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = "回到顶部",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 粘贴数据：二次确认 ----------
    if (showPasteConfirm) {
        AlertDialog(
            onDismissRequest = { showPasteConfirm = false },
            title = { Text("粘贴数据到应用") },
            text = {
                Text(
                    "将把保管的数据粘贴回 Minecraft，并覆盖游戏内的现有数据。是否继续？",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                // 覆盖式操作：红色描边，提醒风险
                OutlinedButton(
                    onClick = {
                        showPasteConfirm = false
                        pasteData()
                    },
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 18.dp,
                        vertical = 0.dp,
                    ),
                ) { Text("确定", fontSize = 14.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showPasteConfirm = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.outline)
                }
            },
        )
    }

    // ---------- 我的数据未被删除：确认后删除备份 ----------
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除备份") },
            text = {
                Text(
                    "请确保您的数据未被删除，点击“确定”删除备份",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) { McDataBackup.clear(context) }
                        backupInfo = null
                    }
                }) { Text("确定", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.outline)
                }
            },
        )
    }
}

/**
 * 单个版本卡片（对应截图：圆形图标 + 版本号 + 右箭头，紫色描边；已安装的版本带标记）
 */
@Composable
private fun VersionItem(
    version: McVersion,
    installed: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = VersionRowStyle.shape,
        colors = CardDefaults.cardColors(containerColor = VersionRowStyle.containerColor()),
        border = VersionRowStyle.border(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = VersionRowStyle.verticalPadding,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(VersionRowStyle.iconBox)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(VersionRowStyle.iconInner),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = version.shortVersion,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (version.beta) {
                    Text(
                        text = "测试版",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 已安装标记：读取设备上已装的 Minecraft 版本号匹配
            if (installed) {
                Text(
                    "已安装",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
