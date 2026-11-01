package com.mcstation.download.data.download

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 下载实时状态快照，供前台服务画进度通知。
 *
 * 只有"有下载在进行"时才有值；没有进行中的任务时置 null，服务据此自行停止。
 */
data class DownloadLiveState(
    /** 通知标题，如 "正在下载 1.21.9" */
    val title: String,
    /** 当前展示任务的已下载字节 */
    val received: Long,
    /** 当前展示任务的总字节（未知为 0） */
    val total: Long,
    /** 合计速度（字节/秒） */
    val speed: Long,
    /** 当前展示任务百分比（0~100） */
    val percent: Int,
    /** 进行中的任务数 */
    val activeCount: Int,
    /** 暂停/失败的其它任务数（用于文案补充） */
    val otherCount: Int,
) {
    val sizeText: String
        get() {
            val got = fmtSize(received)
            return if (total > 0) "$got / ${fmtSize(total)}" else got
        }

    val speedText: String get() = if (speed > 0) "${fmtSize(speed)}/s" else ""
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1073741824L -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 全局唯一的下载状态入口：ViewModel 写，前台服务读 */
object DownloadLiveHub {
    val state = MutableStateFlow<DownloadLiveState?>(null)
}
