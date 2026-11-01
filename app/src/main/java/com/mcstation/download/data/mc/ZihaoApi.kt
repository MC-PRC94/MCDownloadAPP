package com.mcstation.download.data.mc

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * zihao_il「MC版本库」上游（2026-11-01 换源）。
 *
 * ## 为什么换源
 * 原上游小布（mcdl.xbu.hk）2026-10 改版后下载需要 Cloudflare Turnstile 人机验证，
 * 验证组件跑在 App 内嵌 WebView 里。实测在 WebView 内核过旧的设备（如 83.0.4103.106）
 * 上，跨域 iframe 的 postMessage origin 上报有 bug，挑战永远无法完成（表现为一直"正在验证"，
 * 日志见 VerifyJS tag），而该内核在部分厂商 ROM 上无法更新（应用商店搜不到）。
 * zihao 上游为免验证直链（OpenList → OneDrive tempauth），支持 Range 多线程/断点续传，
 * 包内容与小布「去验证包」一致（实测 13634 个条目同名同大小）。
 *
 * ## 接口链路（https://docs.mcarc.top/API文档/）
 *  1. `GET /search_version?s=<主版本>&m=1` —— 模糊搜版本，按 `version` 字段匹配
 *     （`1.26.60.4` 的 version 是 `26.60`，直接搜 `1.26.60.4` 反而查不到）；
 *  2. 命中条目里 `version_all == 1.26.60.4`，取 `link.OneDrive.ARMv8`
 *     = `https://bbk.endyun.ltd/s/1-26-60-4v8`（分享短链）；
 *  3. `POST /get_link`，表单 `s_link=1-26-60-4v8` → `message[0].o_link`
 *     = `https://openlist.endyun.ltd/d/26.X/Minecraft PE 1.26.60.4 v8a.apk`（免验证直链）；
 *  4. 该直链 302 到 OneDrive tempauth 地址（约 1 小时有效），最终地址 200 /
 *     Accept-Ranges: bytes / Range 请求 206，多线程与断点续传可用。
 *     分段下载器每次请求都走 openlist 域名、跟随 302，因此 tempauth 过期会自动拿到新签名。
 *
 * ## 授权提示
 * zihao 文档要求第三方应用使用前先申请授权（zihao_il@qq.com）——尚未申请，存在被限制的风险。
 */
object ZihaoApi {

    private const val TAG = "ZihaoApi"

    /** API 主域名 */
    private const val API = "https://api.mcarc.top"

    /** API 备用域名（站点后端；注意它带 /api 前缀） */
    private const val API_FALLBACK = "https://bbk.endyun.ltd/api"

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000

    /** 最近一次失败原因，供 UI/日志 */
    @Volatile
    var lastFailure: String? = null
        private set

    /** version(小布原始版本号，如 1.26.60.4) → 直链 */
    private val linkCache = HashMap<String, String>()

    /**
     * 取某版本的免验证直链（openlist 地址，302 到 OneDrive）。
     *
     * @param version App 内版本号（26.60.4）或原始版本号（1.26.60.4）均可，内部会归一化匹配
     * @return 直链；该版本不存在 / 接口失败返回 null
     */
    suspend fun directUrl(context: Context, version: String): String? {
        val raw = norm(version)
        synchronized(linkCache) { linkCache[raw] }?.let { return it }
        lastFailure = null

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 1) 搜版本 → 2) 取 OneDrive 分享短链 → 3) 换免验证直链
                val sLink = searchSLink(version) ?: run {
                    if (lastFailure == null) lastFailure = "zihao 上游无此版本"
                    return@withContext null
                }
                val url = exchangeLink(sLink) ?: run {
                    if (lastFailure == null) lastFailure = "zihao 换取直链失败"
                    return@withContext null
                }
                synchronized(linkCache) { linkCache[raw] = url }
                com.mcstation.download.data.log.AppLog.i(TAG, "直链解析成功：$version → $url")
                url
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = "zihao 接口异常：${e.message}"
                com.mcstation.download.data.log.AppLog.e(TAG, "解析直链失败：$version", e)
                null
            }
        }
    }

    /**
     * 探测直链文件的真实大小（详情页显示用）；失败返回 0。
     * HEAD 跟随 302 到 OneDrive 读 Content-Length，失败退化为 Range 0-0 读 Content-Range。
     */
    suspend fun probeSize(context: Context, version: String): Long {
        val url = directUrl(context, version) ?: return 0L
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val encoded = url.replace(" ", "%20")
                val conn = URL(encoded).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                val len = try {
                    if (conn.responseCode in 200..299) conn.contentLengthLong else -1L
                } finally {
                    conn.disconnect()
                }
                if (len > 0) return@withContext len

                // 退化：Range 0-0 读 Content-Range: bytes 0-0/766737551
                val c2 = URL(encoded).openConnection() as HttpURLConnection
                c2.connectTimeout = CONNECT_TIMEOUT
                c2.readTimeout = READ_TIMEOUT
                c2.setRequestProperty("Range", "bytes=0-0")
                try {
                    if (c2.responseCode != 206) return@withContext 0L
                    val cr = c2.getHeaderField("Content-Range")
                    cr?.substringAfterLast('/')?.toLongOrNull() ?: 0L
                } finally {
                    c2.disconnect()
                }
            } catch (e: Exception) {
                com.mcstation.download.data.log.AppLog.e(TAG, "探测大小失败：$version", e)
                0L
            }
        }
    }

    /** 清空缓存（设置页「清理缓存」可用） */
    fun clearCache() {
        synchronized(linkCache) { linkCache.clear() }
        lastFailure = null
    }

    // ================= 内部实现 =================

    /** 版本号归一化：去开头 "1."、小写，让 1.26.60.4 与 26.60.4 视为同一版本（与小布侧规则一致） */
    private fun norm(v: String) = v.trim().removePrefix("1.").lowercase()

    /**
     * 模糊搜索版本，返回目标版本的 OneDrive 分享短链（s_link）。
     * 搜索键是 `version` 字段（如 26.60），从长到短试多个候选，命中 `version_all` 相同的条目为止。
     */
    private fun searchSLink(version: String): String? {
        val target = norm(version)
        val n = version.trim().removePrefix("1.")
        // 候选搜索键：26.60（常见形态）→ 26.60.4 → 1.26.60.4 → 1.26.60
        val candidates = listOfNotNull(
            n.substringBeforeLast('.').takeIf { it != n },
            n,
            version.trim(),
            version.trim().let { if (it.contains('.')) it.substringBeforeLast('.') else null },
        ).distinct()
        for (key in candidates) {
            val body = httpGet("$API/search_version?s=${java.net.URLEncoder.encode(key, "UTF-8")}&m=1")
                ?: continue
            val entry = parseEntries(body).firstOrNull { norm(it.optString("version_all")) == target }
            if (entry != null) {
                val oneDrive = entry.optJSONObject("link")?.optJSONObject("OneDrive")
                val sLink = oneDrive?.optString("ARMv8").orEmpty().trim()
                    .ifEmpty { oneDrive?.optString("ARMv7").orEmpty().trim() }
                if (sLink.isNotEmpty()) return sLink.substringAfterLast('/')
                lastFailure = "zihao 该版本没有 OneDrive 直链"
                return null
            }
        }
        return null
    }

    /** 用 s_link 换免验证直链（o_link）；主域名失败自动换备用域名 */
    private fun exchangeLink(sLink: String): String? {
        for (base in listOf(API, API_FALLBACK)) {
            val body = httpPostForm("$base/get_link", "s_link=${java.net.URLEncoder.encode(sLink, "UTF-8")}")
                ?: continue
            try {
                val root = JSONObject(body)
                if (root.optInt("status") != 200) {
                    lastFailure = "zihao get_link status=${root.optInt("status")}"
                    continue
                }
                val arr = root.optJSONArray("message") ?: continue
                val oLink = (0 until arr.length())
                    .asSequence()
                    .mapNotNull { arr.optJSONObject(it) }
                    .map { it.optString("o_link").trim() }
                    .firstOrNull { it.isNotEmpty() }
                if (oLink != null) return oLink.replace(" ", "%20")
            } catch (e: Exception) {
                lastFailure = "zihao get_link 解析失败：${e.message}"
            }
        }
        return null
    }

    /** 解析 search_version 响应里的 message 数组 */
    private fun parseEntries(body: String): List<JSONObject> {
        val root = JSONObject(body)
        if (root.optInt("status") != 200) {
            lastFailure = "zihao search status=${root.optInt("status")}"
            return emptyList()
        }
        val arr = root.optJSONArray("message") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun open(url: String, method: String, form: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        if (form != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return conn
    }

    private fun httpGet(url: String): String? = try {
        val conn = open(url, "GET", null)
        try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        lastFailure = "zihao search 请求失败：${e.message}"
        null
    }

    private fun httpPostForm(url: String, form: String): String? = try {
        val conn = open(url, "POST", form)
        try {
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        lastFailure = "zihao get_link 请求失败：${e.message}"
        null
    }
}
