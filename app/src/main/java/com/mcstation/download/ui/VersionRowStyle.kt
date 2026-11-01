package com.mcstation.download.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcstation.download.data.cache.DisplayPrefs

/**
 * 版本列表的样式参数（设置页「外观」分组可调）：
 *  - 列表密度：标准 / 紧凑（行高、图标尺寸、行间距）
 *  - 卡片风格：描边 / 轻填充 / 无边框
 *
 * 真实列表（DownloadScreen.VersionItem）与设置页的预览卡共用这里的取值，
 * 保证用户在设置页看到的就是列表里的样子。
 */
object VersionRowStyle {

    val compact: Boolean get() = DisplayPrefs.isCompactList

    val corner: Dp get() = if (compact) 18.dp else 26.dp

    val itemSpacing: Dp get() = if (compact) 6.dp else 10.dp

    val verticalPadding: Dp get() = if (compact) 8.dp else 12.dp

    val iconBox: Dp get() = if (compact) 34.dp else 42.dp

    val iconInner: Dp get() = if (compact) 20.dp else 24.dp

    val shape: RoundedCornerShape get() = RoundedCornerShape(corner)

    /** 卡片容器色 */
    @Composable
    fun containerColor(): Color = when (DisplayPrefs.cardStyle.value) {
        DisplayPrefs.CARD_TINT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    /** 卡片描边；轻填充与无边框风格返回 null */
    @Composable
    fun border(): BorderStroke? = when (DisplayPrefs.cardStyle.value) {
        DisplayPrefs.CARD_OUTLINE -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else -> null
    }
}
