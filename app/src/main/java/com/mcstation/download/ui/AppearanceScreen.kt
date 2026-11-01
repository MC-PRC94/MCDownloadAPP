@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.mcstation.download.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcstation.download.data.cache.DisplayPrefs
import com.mcstation.download.ui.theme.DEFAULT_THEME_COLOR_ID
import com.mcstation.download.ui.theme.ThemeColorOptions

/**
 * 外观页（从设置页的"外观"分组拆出来的独立页面）。
 *
 * 内容：[颜色] 深浅模式 + 主题色（12 色） / [列表样式] 列表密度 + 卡片风格 / [预览] 实时预览 + 恢复默认
 * 全部即时生效（读的是 Compose 可观察状态），改完立即全局重建，不需要重启。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ---------- 顶部栏（与声明页/开源许可页一致） ----------
        TopAppBar(
            title = { Text("外观") },
            modifier = Modifier.padding(top = 14.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            // ================= 颜色 =================
            SettingCard(title = "颜色") {
                Text("深浅模式", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NightModeOption.entries.forEach { opt ->
                        SettingChip(
                            label = opt.label,
                            selected = DisplayPrefs.nightMode.value == opt.id,
                            onClick = { DisplayPrefs.setNightMode(context, opt.id) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("主题色", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                val currentThemeId = DisplayPrefs.themeColorId.value
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ThemeColorOptions.forEach { option ->
                        val selected =
                            if (option.id == DEFAULT_THEME_COLOR_ID) currentThemeId == null
                            else currentThemeId == option.id
                        ThemeSwatch(
                            color = option.swatch,
                            label = option.label,
                            selected = selected,
                            onClick = {
                                DisplayPrefs.setThemeColor(
                                    context,
                                    if (option.id == DEFAULT_THEME_COLOR_ID) null else option.id,
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ================= 列表样式 =================
            SettingCard(title = "列表样式") {
                Text("列表密度", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DensityOption.entries.forEach { opt ->
                        SettingChip(
                            label = opt.label,
                            selected = DisplayPrefs.listDensity.value == opt.id,
                            onClick = { DisplayPrefs.setListDensity(context, opt.id) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("卡片风格", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardStyleOption.entries.forEach { opt ->
                        SettingChip(
                            label = opt.label,
                            selected = DisplayPrefs.cardStyle.value == opt.id,
                            onClick = { DisplayPrefs.setCardStyle(context, opt.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ================= 预览 =================
            SettingCard(title = "预览") {
                AppearancePreview()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { DisplayPrefs.resetAppearance(context) }) {
                        Text("恢复默认外观")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

// ================= 外观选项与小控件 =================

private enum class NightModeOption(val id: String, val label: String) {
    System(DisplayPrefs.NIGHT_SYSTEM, "跟随系统"),
    Light(DisplayPrefs.NIGHT_LIGHT, "浅色"),
    Dark(DisplayPrefs.NIGHT_DARK, "深色"),
}

private enum class DensityOption(val id: String, val label: String) {
    Standard(DisplayPrefs.DENSITY_STANDARD, "标准"),
    Compact(DisplayPrefs.DENSITY_COMPACT, "紧凑"),
}

private enum class CardStyleOption(val id: String, val label: String) {
    Outline(DisplayPrefs.CARD_OUTLINE, "描边"),
    Tint(DisplayPrefs.CARD_TINT, "轻填充"),
    Flat(DisplayPrefs.CARD_FLAT, "无边框"),
}

/** 外观页里的单选 chip（与主列表筛选 chip 同一套配色） */
@Composable
private fun SettingChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** 主题色圆形色块，选中时打勾 */
@Composable
private fun ThemeSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    ),
                ),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 外观预览：按当前密度/卡片风格渲染一条示例版本行，让用户在设置页就能看到效果，
 * 不用来回切换页面确认。样式取值与主列表共用 VersionRowStyle。
 */
@Composable
private fun AppearancePreview() {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    "1.21.9",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "预览",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
