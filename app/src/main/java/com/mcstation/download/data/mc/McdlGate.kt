package com.mcstation.download.data.mc

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 小布のMC版本库：下载授权（Turnstile 人机验证 → gate 直链）。
 *
 * ## 为什么需要这个
 * 站点在 2026-10 改版后，`/api/data` 里**不再下发任何直链**，`v8aFiles` 只剩标签。
 * 网页端的下载流程变成：
 *   1. 浏览器里跑 Cloudflare Turnstile，拿到一个 token；
 *   2. `POST /api/turnstile/verify {response: token, meta: {type, version, arch, label}}`；
 *   3. 服务端校验通过后返回 `gate`（授权后的下载直链），网页用 a 标签跳转下载。
 *
 * App 拿不到直链就是这个原因。这里用**内嵌 WebView** 跑第 1 步（Cloudflare 官方支持
 * WebView，见 Turnstile「Mobile implementation」文档），第 2、3 步由原生代码完成。
 *
 * **源不变**：整个流程仍然只跟 mcdl.xbu.hk 打交道，下载的仍是站点同一份「去验证版」包，
 * 不做任何跨渠道替换（与 [McdlApi] 的「失败绝不换渠道」原则一致）。
 */
object McdlGate {

    const val HOST = "https://mcdl.xbu.hk"

    /**
     * 正常等待时长。超时不再直接关窗（用户以前看到的就是"转了两分钟自己关了"），
     * 而是置 [expired] 让弹窗显示"超时，可重试"，再给 [GRACE_MS] 的宽限让用户决定。
     */
    private const val WAIT_MS = 60_000L

    /** 超时后留给用户点「重试 / 取消」的宽限时间 */
    private const val GRACE_MS = 30_000L

    /** 超时提示文案（弹窗显示，也作为失败原因） */
    const val TIMEOUT_MSG = "验证等待超时，请重试"

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 20_000

    /** 弹窗状态：非空 = 需要用户在 WebView 里完成一次人机验证（Compose 观察这个值） */
    var request by mutableStateOf<Request?>(null)
        private set

    /**
     * 等待已超时（但流程还没结束）：弹窗据此把"转圈"换成"超时提示 + 重试"。
     * 用户点重试会把它清回 false，见 [extendWait]。
     */
    var expired by mutableStateOf(false)
        private set

    /** 一次验证请求 */
    class Request internal constructor(val rawVersion: String) {
        internal val deferred = CompletableDeferred<String?>()

        /** 等待截止时间（elapsedRealtime）。用户点「重试」时会往后延，见 [extendWait] */
        @Volatile
        internal var deadline: Long = 0L
    }

    /** 最近一次失败原因，供 UI 提示 */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 同一版本在一次会话里只验证一次，避免反复弹窗 */
    private val gateCache = HashMap<String, String>()

    /**
     * 取某版本的授权直链（gate）。需要用户配合时会在主线程弹出验证窗。
     *
     * @param rawVersion 站点接口里的原始版本号，例如 `1.26.60.4`（不是 App 内的 `26.60.4`）
     * @param label 包体标签，必须与网页端点的那颗按钮一致（「去验证版」/ 老版本为空串）。
     *   服务端按 meta 精确查文件，标签不对会返回 404。
     * @param type 分表（stable/beta），由 [McdlApi.verifyMetaOf] 一并给出
     * @return 可下载的直链；用户取消 / 验证失败 / 超时返回 null
     */
    suspend fun gate(
        context: Context,
        rawVersion: String,
        label: String = "",
        type: String = "stable",
    ): String? {
        gateCache[rawVersion]?.let { return it }
        lastFailure = null

        val startedAt = android.os.SystemClock.elapsedRealtime()
        val token = withContext(Dispatchers.Main) {
            val req = Request(rawVersion)
            req.deadline = startedAt + WAIT_MS
            expired = false
            request = req
            try {
                // 分片等待而不是一次性 withTimeoutOrNull：用户点「重试」时 deadline 会被
                // 往后推，一次性超时无法延长，会出现"点了重试只剩几秒"的情况。
                // 到达 deadline 也不立刻结束：置 expired 让弹窗给出提示，再宽限 GRACE_MS。
                var out: String? = null
                while (true) {
                    if (req.deferred.isCompleted) {
                        out = runCatching { req.deferred.await() }.getOrNull()
                        break
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - req.deadline >= GRACE_MS) break
                    if (now >= req.deadline && !expired) {
                        expired = true
                        lastFailure = TIMEOUT_MSG
                        com.mcstation.download.data.log.AppLog.e(
                            "McdlGate",
                            "等待验证 token 超时（${now - startedAt}ms），保留弹窗等待用户重试",
                        )
                    }
                    val slice = (req.deadline + GRACE_MS - now).coerceIn(1L, 500L)
                    val r = withTimeoutOrNull(slice) { req.deferred.await() }
                    if (r != null) {
                        out = r
                        break
                    }
                }
                out
            } finally {
                request = null
                expired = false
            }
        }
        if (token.isNullOrEmpty()) {
            // 不覆盖验证组件已经给出的真实原因（超时/组件错误等），否则一律显示成
            // "未完成来源验证"，排查时会被误判成"用户自己关掉了弹窗"
            if (lastFailure.isNullOrEmpty()) lastFailure = "未完成来源验证"
            com.mcstation.download.data.log.AppLog.e(
                "McdlGate",
                "未拿到验证 token（耗时 ${android.os.SystemClock.elapsedRealtime() - startedAt}ms，" +
                    "原因=${lastFailure}）",
            )
            return null
        }
        com.mcstation.download.data.log.AppLog.i(
            "McdlGate",
            "已拿到验证 token（耗时 ${android.os.SystemClock.elapsedRealtime() - startedAt}ms），开始换取授权",
        )

        val gateUrl = withContext(Dispatchers.IO) { exchange(token, rawVersion, label, type) }
        if (gateUrl.isNullOrEmpty()) return null

        // gate 是一次性授权入口（并发分段请求同一 token 会 404），
        // 这里先原地解析出 302 后的真实文件直链，再把直链交给分段下载器
        val finalUrl = withContext(Dispatchers.IO) { resolveGate(gateUrl) }
        if (finalUrl.isNullOrEmpty()) return null

        gateCache[rawVersion] = finalUrl
        return finalUrl
    }

    /**
     * 解析 gate：请求一次（不跟随重定向），取 302 的 Location 作为真实直链。
     * 请求头与 [postVerify] 保持一致（同 UA + 源站 cookie），避免校验不一致被拒。
     * 若 gate 直接 200 返回文件，则原样返回 gate 地址。
     */
    private fun resolveGate(gateUrl: String): String? {
        return try {
            val cookie = android.webkit.CookieManager.getInstance().getCookie(HOST)
            val conn = URL(gateUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("User-Agent", System.getProperty("http.agent") ?: "okhttp/4")
            conn.setRequestProperty("Referer", "$HOST/")
            conn.setRequestProperty("Accept", "*/*")
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)

            val code = conn.responseCode
            when {
                code in 300..399 -> {
                    val loc = conn.getHeaderField("Location").orEmpty().trim()
                    if (loc.isEmpty()) {
                        lastFailure = "gate 返回 $code 但没有跳转地址"
                        null
                    } else when {
                        loc.startsWith("http://") || loc.startsWith("https://") -> loc
                        loc.startsWith("/") -> HOST + loc
                        else -> "$HOST/$loc"
                    }.also { com.mcstation.download.data.log.AppLog.i("McdlGate", "gate → $it") }
                }
                code in 200..299 -> gateUrl.also {
                    com.mcstation.download.data.log.AppLog.i("McdlGate", "gate 直接返回文件内容（HTTP $code）")
                }
                else -> {
                    lastFailure = "下载授权链接返回 HTTP $code"
                    com.mcstation.download.data.log.AppLog.e("McdlGate", "gate 解析失败 HTTP $code")
                    null
                }
            }
        } catch (e: Exception) {
            lastFailure = "解析下载授权失败：${e.message}"
            com.mcstation.download.data.log.AppLog.e("McdlGate", "解析 gate 失败", e)
            null
        }
    }

    /** WebView 里拿到 token 后回调（由 JS 桥调用） */
    fun submitToken(token: String?) {
        request?.deferred?.complete(token)
    }

    /**
     * 验证组件报错：只记下真实原因，**不结束流程**。
     *
     * 早先这里是 complete(null)，效果是组件一报错弹窗立刻消失、下载报"已取消来源验证"，
     * 用户完全不知道发生了什么。现在错误留在弹窗里让用户决定重试还是取消，
     * 若最终取消，[lastFailure] 里已经是准确原因（如"验证组件报错（300030）"）而不是笼统的默认文案。
     */
    fun noteError(message: String) {
        lastFailure = message
        com.mcstation.download.data.log.AppLog.e("McdlGate", "验证组件报错（流程继续等待）：$message")
    }

    /** 用户在弹窗里点「重试」：把等待截止时间重新往后延一个完整周期 */
    fun extendWait() {
        val req = request ?: return
        req.deadline = android.os.SystemClock.elapsedRealtime() + WAIT_MS
        // 上一次的错误原因已经不再是当前状态，清掉；若这次仍失败会重新写入
        lastFailure = null
        expired = false
        com.mcstation.download.data.log.AppLog.i("McdlGate", "用户点击重试，等待时间重新计算")
    }

    /** 用户关掉弹窗 = 取消 */
    fun cancel() {
        com.mcstation.download.data.log.AppLog.i(
            "McdlGate",
            "用户关闭了验证弹窗（按取消处理，此前错误=${lastFailure ?: "无"}）",
        )
        request?.deferred?.complete(null)
    }

    /**
     * 用 Turnstile token 换 gate。
     *
     * token 是**一次性**的：第一次请求无论成败都会被服务端消费，所以必须先按准确的
     * 分表（[type]，来自版本表）请求；另一张表只作为兜底试一次（大概率 token 已失效）。
     */
    private fun exchange(token: String, rawVersion: String, label: String, type: String): String? {
        postVerify(token, rawVersion, type, label)?.let { return it }
        val other = if (type == "beta") "stable" else "beta"
        return postVerify(token, rawVersion, other, label)
    }

    private fun postVerify(
        token: String,
        rawVersion: String,
        type: String,
        label: String,
    ): String? {
        // 与网页端点击按钮发出的 meta 完全一致（app.js：cat / type / version / arch / label）。
        // 少一个字段或标签不对，服务端查不到文件就会返回 404。
        val meta = JSONObject().apply {
            put("cat", "be")
            put("type", type)
            put("version", rawVersion)
            put("arch", "v8a")
            put("label", label)
        }
        val payload = JSONObject().apply {
            put("response", token)
            put("meta", meta)
        }.toString()

        val conn = try {
            (URL("$HOST/api/turnstile/verify").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Referer", "$HOST/")
                setRequestProperty("Origin", HOST)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", System.getProperty("http.agent") ?: "okhttp/4")
            }
        } catch (e: Exception) {
            lastFailure = "网络不可用：${e.message}"
            return null
        }

        return try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()

            if (code !in 200..299) {
                lastFailure = "验证接口返回 HTTP $code"
                // 记下 meta 与响应体：服务端 404 通常是「meta 里的标签/版本查不到文件」，
                // 只有拿到原始响应才能判断，不然只能瞎猜
                com.mcstation.download.data.log.AppLog.e(
                    "McdlGate", "verify 失败 HTTP $code meta=$meta body=${body.take(300)}",
                )
                return null
            }
            val json = JSONObject(body)
            if (!json.optBoolean("ok")) {
                lastFailure = json.optString("error").ifEmpty { "验证未通过" }
                return null
            }
            val gate = json.optString("gate").trim()
            if (gate.isEmpty()) return null
            // 服务端可能返回相对路径（如 /api/dl-gate?token=...），补全为完整 URL
            val full = when {
                gate.startsWith("http://") || gate.startsWith("https://") -> gate
                gate.startsWith("/") -> HOST + gate
                else -> "$HOST/$gate"
            }
            com.mcstation.download.data.log.AppLog.i(
                "McdlGate", "已换取下载授权：$rawVersion（$type）→ $full",
            )
            full
        } catch (e: Exception) {
            lastFailure = "换取授权失败：${e.message}"
            com.mcstation.download.data.log.AppLog.e("McdlGate", "换取授权失败", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 清掉授权缓存（设置里「清理缓存」调用） */
    fun clearCache() {
        gateCache.clear()
        lastFailure = null
    }
}
