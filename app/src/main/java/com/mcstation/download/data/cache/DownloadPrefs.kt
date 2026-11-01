package com.mcstation.download.data.cache

import android.content.Context

/**
 * 下载相关偏好（SharedPreferences 落盘）：
 * - 上次使用的下载线程数 / 装后删除（下载确认框默认值 = 上次的选择）
 * - 自动检查更新开关（设置页）
 * - 设置页里的"默认线程数"（与上次使用共用一个键，改一处即默认值）
 */
object DownloadPrefs {

    private const val FILE = "download_prefs"
    private const val KEY_THREADS = "thread_count"
    private const val KEY_DELETE_AFTER_INSTALL = "delete_after_install"
    private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"

    const val DEFAULT_THREADS = 12
    const val MAX_THREADS = 16

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 下载线程数（1~16） */
    fun threadCount(context: Context): Int =
        sp(context).getInt(KEY_THREADS, DEFAULT_THREADS).coerceIn(1, MAX_THREADS)

    fun setThreadCount(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_THREADS, value.coerceIn(1, MAX_THREADS)).apply()
    }

    /** 安装完后删除安装包 */
    fun deleteAfterInstall(context: Context): Boolean =
        sp(context).getBoolean(KEY_DELETE_AFTER_INSTALL, true)

    fun setDeleteAfterInstall(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_DELETE_AFTER_INSTALL, value).apply()
    }

    /** 启动时自动检查更新 */
    fun autoCheckUpdate(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_CHECK_UPDATE, true)

    fun setAutoCheckUpdate(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_CHECK_UPDATE, value).apply()
    }

    // ================= 暂停中的下载任务 =================

    private const val KEY_PAUSED = "paused_tasks"

    /** 暂停中的任务文件名集合（持久化：重启后保持暂停，不被"断点恢复"自动继续） */
    fun pausedTasks(context: Context): Set<String> =
        sp(context).getStringSet(KEY_PAUSED, emptySet()) ?: emptySet()

    fun addPausedTask(context: Context, fileName: String) {
        val set = pausedTasks(context).toMutableSet()
        set.add(fileName)
        sp(context).edit().putStringSet(KEY_PAUSED, set).apply()
    }

    fun removePausedTask(context: Context, fileName: String) {
        val set = pausedTasks(context).toMutableSet()
        set.remove(fileName)
        sp(context).edit().putStringSet(KEY_PAUSED, set).apply()
    }

    // ================= 搜索历史 =================

    private const val KEY_RECENT_QUERY = "recent_queries"
    private const val MAX_RECENT_QUERY = 6

    /** 最近搜索过的版本号（最新在前，最多 6 条） */
    fun recentQueries(context: Context): List<String> =
        (sp(context).getString(KEY_RECENT_QUERY, "") ?: "")
            .split("\u0001")
            .filter { it.isNotBlank() }

    /** 记录一次搜索（去重、最新置顶、上限 6 条） */
    fun addRecentQuery(context: Context, query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val list = recentQueries(context).toMutableList()
        list.remove(q)
        list.add(0, q)
        while (list.size > MAX_RECENT_QUERY) list.removeAt(list.lastIndex)
        sp(context).edit().putString(KEY_RECENT_QUERY, list.joinToString("\u0001")).apply()
    }

    fun clearRecentQueries(context: Context) {
        sp(context).edit().remove(KEY_RECENT_QUERY).apply()
    }

    // ================= 首次使用提示 =================

    private const val KEY_LIST_HINT_READ = "list_hint_read"

    /** 版本列表"怎么快速找版本"提示是否已经看过 */
    fun listHintRead(context: Context): Boolean =
        sp(context).getBoolean(KEY_LIST_HINT_READ, false)

    fun setListHintRead(context: Context) {
        sp(context).edit().putBoolean(KEY_LIST_HINT_READ, true).apply()
    }
}
