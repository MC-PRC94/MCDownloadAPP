package com.mcstation.download.data.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mcstation.download.data.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级下载状态：任务列表、协程作用域、取消句柄都放在这里。
 *
 * 目的：下载不能再绑在 ViewModel 的生命周期上（Activity 销毁 = 下载中断）。
 * 把状态与协程提到进程级后：
 *  - 用户退出界面/切后台，下载继续跑（配合 [DownloadForegroundService] 保活）
 *  - 重新进入应用时，ViewModel 直接读同一份任务列表，进度不丢
 *
 * Compose 通过 [tasks] 的 mutableStateOf 观察变化，与原来的单 ViewModel 行为一致。
 */
object DownloadStore {

    /** 全部下载任务（下载中 / 暂停 / 完成 / 失败） */
    var tasks: List<DownloadTask> by mutableStateOf(emptyList())

    /** 下载协程作用域：不随 Activity 销毁而取消 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 任务 id -> 正在跑的协程（暂停/取消时用） */
    val jobs: MutableMap<Long, Job> = mutableMapOf()

    /** 任务自增 id */
    var nextTaskId: Long = 1L

    /** 启动时的断点恢复只做一次，避免 ViewModel 重建时重复恢复 */
    var resumedOnStartup: Boolean = false
}
