package com.mcstation.download.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mcstation.download.data.log.AppLog
import com.mcstation.download.data.mc.McdlGate

/**
 * 下载来源验证弹窗。
 *
 * 源站（mcdl.xbu.hk）改版后不再在接口里给直链，改为「Cloudflare Turnstile 人机验证
 * → 服务端签发授权链接」。这里把官方验证组件跑在 WebView 里（Cloudflare 明确支持 WebView），
 * 验证通过后由 [McdlGate] 用 token 换取下载授权，**源站与渠道都不变**。
 *
 * **不显示源站页面**：WebView 真实请求 `https://mcdl.xbu.hk/`，但主文档在
 * [android.webkit.WebViewClient.shouldInterceptRequest] 里被替换成自带的极简验证页
 * （源站首页根本不会下载/渲染）。这样验证组件跑在**真实的源站 origin** 下——
 * referrer、cookie、同源语义与浏览器里完全一致，Turnstile 的 hostname 校验与
 * 风控检查都按真实站点对待；用户全程只看到一个验证框，不会看到源站界面。
 *
 * **旧内核提示**：Turnstile 的挑战依赖跨域 iframe 的 postMessage 握手，WebView 83
 * （2020 年）这类老内核有 origin 上报 bug，挑战永远无法完成（实测表现为一直"正在验证"）。
 * 内核大版本 < 100 时在弹窗里显示提示并给出更新入口。
 */
@Composable
fun McdlVerifyDialog() {
    val request = McdlGate.request ?: return

    Dialog(
        onDismissRequest = { McdlGate.cancel() },
        // 点弹窗外部不取消：验证过程可能持续几十秒，误触关闭会被当成"用户取消验证"
        // （表现为下载失败"已取消来源验证"，排查时极容易误判成网络问题）
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        var errorText by remember { mutableStateOf<String?>(null) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // 重试时重新加载验证页（组件报错/超时后 Turnstile 实例多半已不可用，reload 最干净）
        val webViewRef = remember { mutableStateOf<WebView?>(null) }
        // 等待超时由 McdlGate 置位：弹窗不自己关掉，改成显示提示 + 重试按钮
        val expired = McdlGate.expired
        // 旧内核检测（大版本 < 100 = 2020 年前后的老 WebView，Turnstile 挑战大概率无法完成）
        val oldKernel = remember { runCatching { webViewMajorVersion() }.getOrDefault(-1) in 1..99 }

        Card(
            // Turnstile 组件固定 300px 宽，卡片内宽要 ≥ 300dp 才不被裁切：
            // 360dp 上限 × 94% − 36dp 内边距 ≈ 302dp；窄屏（<360dp）也能自适应不溢出
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.94f)
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    "验证下载来源",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "源站启用了下载验证，完成后会自动开始下载，仅需一次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )

                // 旧内核提示：不是不让他试（万一能过），是让他知道慢/失败的原因和出路
                if (oldKernel) {
                    Text(
                        // TODO(文案)：这句提示语由用户定稿
                        "系统 WebView 内核过旧，验证可能无法完成，建议更新系统组件或改用浏览器下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TextButton(onClick = { openWebViewUpdate(ctx) }) { Text("更新系统 WebView") }
                }

                Box(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { ctx ->
                            createVerifyWebView(
                                ctx = ctx,
                                onError = { msg -> errorText = msg },
                            ).also { webViewRef.value = it }
                        },
                        // 弹窗关闭后及时释放 WebView，避免继续跑 JS / 占内存
                        onRelease = { it.stopLoading(); it.destroy() },
                    )
                }

                // 等待中给一条进度条：让"在跑"和"卡死"在视觉上能区分开
                if (errorText == null && !expired) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth(),
                    )
                }

                val shownError = errorText ?: if (expired) McdlGate.TIMEOUT_MSG else null
                shownError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (shownError != null) {
                        // 重试：重新走一遍"请求首页 → 拦截替换"流程并重置等待
                        // （Turnstile 实例失败后无法复用，loadUrl 重新加载最干净）
                        TextButton(onClick = {
                            errorText = null
                            // 重新走一遍"请求首页 → 拦截替换"的流程（Turnstile 实例失败后无法复用）
                            webViewRef.value?.loadUrl("${McdlGate.HOST}/")
                            McdlGate.extendWait()
                        }) { Text("重试") }
                    }
                    TextButton(onClick = { McdlGate.cancel() }) { Text("取消") }
                }
            }
        }
    }
}

/**
 * 每次弹窗新建一个 WebView，关闭时销毁，避免持有 Activity 泄漏。
 *
 * 关键：真实请求 `https://mcdl.xbu.hk/`，由 [android.webkit.WebViewClient.shouldInterceptRequest]
 * 把主文档换成 [VERIFY_HTML] —— 页面 origin 是真实的源站 https origin（对 Turnstile
 * 的 hostname 校验/风控更友好），但站点 UI 不会出现（主文档被替换，源站 JS 不运行）。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createVerifyWebView(ctx: Context, onError: (String) -> Unit): WebView {
    val openedAt = android.os.SystemClock.elapsedRealtime()
    val webView = WebView(ctx)
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        // Turnstile 要求环境稳定：这里刻意不动 userAgent（改 UA 会让验证失败）
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        allowFileAccess = false
        allowContentAccess = false
        // 验证脚本走磁盘缓存：预热过就直接命中，不必再等一次网络往返
        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
    }
    // Turnstile 依赖 cookie / 本地存储在会话内保持
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

    // WebView 内核版本直接决定 Turnstile 能不能跑起来（内核过旧会一直转圈），
    // 记一笔，排查"验证不出结果"时能第一时间排除环境问题
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching {
            WebView.getCurrentWebViewPackage()?.let {
                AppLog.i("Verify", "WebView 内核：${it.packageName} ${it.versionName}")
            }
        }
    }

    webView.addJavascriptInterface(object {
        @JavascriptInterface
        fun onToken(token: String) {
            AppLog.i("Verify", "已拿到 Turnstile token（长度 ${token.length}）")
            webView.post { McdlGate.submitToken(token) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            AppLog.e("Verify", "验证组件报错：$message")
            // 造成失败的具体原因交给 McdlGate 记账（不结束流程）。之前这里直接 submitError
            // 会让 gate() 立刻返回 null、弹窗随即消失，用户只看到"弹窗自己关了"，
            // 既不知道原因也没机会重试。现在改为留在弹窗，由用户点「重试」或「取消」。
            McdlGate.noteError(message)
            webView.post { onError(message) }
        }

        /** 关键阶段打点：脚本是否加载成功、render 是否被调用——排查"验证不出框"用 */
        @JavascriptInterface
        fun onStage(stage: String) {
            AppLog.i(
                "Verify",
                "验证页阶段：$stage（页面加载后 ${android.os.SystemClock.elapsedRealtime() - openedAt}ms）",
            )
        }
    }, BRIDGE_NAME)

    webView.webViewClient = object : WebViewClient() {
        /**
         * 只拦「主框架跳到别处」，**必须放行子框架**。
         *
         * 旧实现无条件 `return true`，把 Turnstile 挑战用的 iframe（challenges.cloudflare.com）
         * 一并拦掉了：组件画得出来、挑战请求却发不出去，表现就是永远转圈直到超时。
         */
        override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
            val r = req ?: return true
            if (!r.isForMainFrame) return false
            // 主框架一律不许跳走：验证页是自带的，任何跳转都只会露出站外/站内界面
            return true
        }

        /** API 21–23 只有这个重载，拿不到 isForMainFrame：非 http(s) 拦，http(s) 放行 */
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val u = url.orEmpty()
            return !(u.startsWith("http://") || u.startsWith("https://"))
        }

        /**
         * 用真·https 页面承载验证组件：请求 `https://mcdl.xbu.hk/`，在
         * [shouldInterceptRequest] 里把**主文档**换成自带的极简验证页，
         * 其余请求（Turnstile 脚本、挑战 iframe、/api 接口）全部原样放行。
         *
         * 之前用 loadDataWithBaseURL「伪装」源站 origin，组件能画出来，但挑战
         * 本身经常无限"正在验证…"（真机录屏 45 秒 + Turnstile 自己弹"卡住？"），
         * 而同样的组件在浏览器里秒过——差异就在页面环境：真页面的 origin、
         * referrer、cookie 语义都是真的，Cloudflare 的风控检查更容易放行。
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            // 只换主文档，且只认首页路径；子资源里不会有对 "/" 的请求，
            // 万一有（理论上不存在），放行走真实网络也不会露站点 UI
            if (!request.isForMainFrame) return null
            if (url != "${McdlGate.HOST}/" && !url.startsWith("${McdlGate.HOST}/?")) return null
            AppLog.i("Verify", "主文档请求被替换为内置验证页：$url")
            return WebResourceResponse("text/html", "utf-8", VERIFY_HTML.byteInputStream())
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            // title 一并落日志：若是站点标题说明拦截失败、露出了真实站点页，需立即排查
            AppLog.i(
                "Verify",
                "验证页 HTML 加载完成（${android.os.SystemClock.elapsedRealtime() - openedAt}ms，title=${view?.title}）",
            )
        }
    }

    // Turnstile 的不少失败只在 JS 控制台留痕（CSP 拦截、域名未授权、网络异常等），
    // 把控制台内容一并落盘，排查时能判断是"脚本没起来"还是"环境被风控"。
    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
            val text = msg?.message().orEmpty()
            val src = msg?.sourceId().orEmpty()
            if (text.isNotEmpty()) AppLog.i("VerifyJS", "${msg?.messageLevel()} $text ($src:${msg?.lineNumber()})")
            return true
        }
    }

    // 真·https 加载：主文档由 shouldInterceptRequest 换成内置验证页（见其注释）
    webView.loadUrl("${McdlGate.HOST}/")
    return webView
}

private const val BRIDGE_NAME = "MCDLB"
private const val WARM_BRIDGE_NAME = "MCDLWARM"

/** 系统 WebView 内核大版本；拿不到（API<26 / 异常）返回 -1 */
private fun webViewMajorVersion(): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return -1
    return runCatching {
        WebView.getCurrentWebViewPackage()?.versionName
            ?.substringBefore('.')?.toIntOrNull()
    }.getOrNull() ?: -1
}

/** 跳到应用商店的「Android System WebView」详情页；商店不可用时退回网页版链接 */
private fun openWebViewUpdate(ctx: Context) {
    val pkg = "com.google.android.webview"
    try {
        ctx.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=$pkg"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** 预热完成/失败/超时后销毁 WebView 用的统一入口 */
private fun destroyQuietly(webView: WebView) {
    runCatching { webView.stopLoading(); webView.destroy() }
}

/**
 * 预热验证组件：应用启动后调用一次，在后台把 Cloudflare Turnstile 脚本拉进 WebView 缓存。
 *
 * 验证弹窗慢的主因是「弹窗时才新建 WebView + 现场下载验证脚本」。脚本 URL 与
 * [VERIFY_HTML] 里完全一致（缓存键相同），预热命中缓存后，真正弹窗时脚本近乎秒开。
 * WebView 数据目录全局共享，预热实例与弹窗实例互相独立、用完即销毁。
 */
fun warmUpVerifyWebView(context: Context) {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    main.post {
        // 已有预热在跑就直接跳过（应用生命周期内最多预热一次）
        if (verifyWarmupRunning) return@post
        verifyWarmupRunning = true
        val startedAt = android.os.SystemClock.elapsedRealtime()
        AppLog.i("Verify", "预热验证组件：开始")
        try {
            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }
            val done = booleanArrayOf(false)
            fun destroyOnce(reason: String) {
                if (!done[0]) {
                    done[0] = true
                    AppLog.i(
                        "Verify",
                        "预热结束（$reason，耗时 ${android.os.SystemClock.elapsedRealtime() - startedAt}ms）",
                    )
                    destroyQuietly(webView)
                }
            }
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onReady() { main.post { destroyOnce("脚本已缓存") } }
            }, WARM_BRIDGE_NAME)
            // 只加载脚本、不 render：拿到 token 与用户交互无关，这里只为缓存脚本
            webView.loadDataWithBaseURL(
                "${McdlGate.HOST}/",
                WARM_HTML,
                "text/html",
                "UTF-8",
                null,
            )
            // 20 秒兜底：脚本加载再慢也不能让预热 WebView 一直挂着
            main.postDelayed({ destroyOnce("超时兜底") }, 20_000)
        } catch (e: Exception) {
            // 预热失败不影响功能，真正弹窗时按原流程现场加载
            AppLog.e("Verify", "预热失败：${e.message}")
        }
    }
}

@Volatile
private var verifyWarmupRunning = false

/** 预热页：只下载 Turnstile 脚本（URL 与验证页完全一致），加载完立刻通知销毁 */
private val WARM_HTML = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<link rel="preconnect" href="https://challenges.cloudflare.com" crossorigin>
</head><body>
<script>
  window.__mcdlWarmBoot = function () {
    try { window.MCDLWARM.onReady(); } catch (e) {}
  };
  var s = document.createElement('script');
  s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=__mcdlWarmBoot';
  s.onload = function () { try { window.MCDLWARM.onReady(); } catch (e) {} };
  s.onerror = function () { try { window.MCDLWARM.onReady(); } catch (e) {} };
  document.head.appendChild(s);
</script>
</body></html>
""".trimIndent()

/**
 * 自带验证页：白底、居中、只有一行提示 + Turnstile 组件。
 * 与源站页面没有任何关系，所以不存在「露出网站界面」的问题。
 *
 * 提速要点（2026-10-31）：
 *  - 脚本与 siteKey **并行**取，谁先到都不互相等；
 *  - 看门狗从 20s/12s 收紧到 10s/6s，失败 10 秒内就能看到原因，而不是干等两三分钟；
 *  - `retry: 'never'`：Turnstile 默认的自动重试会把"失败"藏起来反复重来，
 *    用户只能看着转圈；现在直接报错，由用户点「重试」（页面已缓存，重来很快）；
 *  - `size: 'flexible'`：组件宽度自适应容器，避免 300px 定宽在小卡片里被裁切；
 *  - 每个阶段上报耗时（onStage 带 `+xxms`），下次出问题能从日志直接看出卡在哪一步。
 */
private val VERIFY_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="preconnect" href="https://challenges.cloudflare.com" crossorigin>
<style>
  html, body { margin:0; padding:0; height:100%; background:#ffffff; }
  .wrap {
    display:flex; flex-direction:column; align-items:center; justify-content:center;
    height:100%; font-family:system-ui,-apple-system,sans-serif; padding:0 8px;
    box-sizing:border-box;
  }
  .tip { font-size:13px; color:#8a8a8a; margin-bottom:14px; }
  #tk { width:100%; display:flex; justify-content:center; }
</style>
</head>
<body>
<div class="wrap">
  <div class="tip" id="tip">正在准备验证…</div>
  <div id="tk"></div>
</div>
<script>
(function () {
  var FALLBACK_SITEKEY = '0x4AAAAAAEd4kNsJmRPtQfrd';
  var T0 = (window.performance && performance.now) ? performance.now() : Date.now();
  function ms() {
    var n = (window.performance && performance.now) ? performance.now() : Date.now();
    return Math.round(n - T0);
  }
  function bridge() { try { return window.MCDLB || null; } catch (e) { return null; } }
  function stage(s) { var b = bridge(); if (b && b.onStage) { try { b.onStage(s + ' +' + ms() + 'ms'); } catch (e) {} } }
  function setTip(t) { var el = document.getElementById('tip'); if (el) el.textContent = t; }

  var failed = false;
  var loaded = false;
  var rendered = false;
  var renderWatchdog = null;
  var loadWatchdog = null;

  function fail(msg) {
    // 只报第一次：自动重试/多个回调可能连续报错，重复上报会让日志失真
    if (failed) return;
    failed = true;
    clearTimeout(loadWatchdog);
    clearTimeout(renderWatchdog);
    setTip(msg);
    stage('fail');
    var b = bridge();
    if (b && b.onError) { try { b.onError(msg); } catch (e) {} }
  }

  function render(siteKey) {
    if (failed || rendered) return;
    rendered = true;
    clearTimeout(renderWatchdog);

    if (typeof turnstile === 'undefined' || !turnstile || !turnstile.render) {
      fail('无法加载验证组件，请检查网络');
      return;
    }
    setTip('请完成下方验证');
    stage('render');
    try {
      turnstile.render('#tk', {
        sitekey: siteKey,
        language: 'zh-CN',
        theme: 'light',
        size: 'flexible',
        // 自动重试会把失败藏起来（每 8 秒静默重来一次，用户只能干等）；
        // 这里改成不重试，失败立刻报出来，由用户点「重试」
        retry: 'never',
        'refresh-expired': 'auto',
        callback: function (token) {
          setTip('验证通过，正在获取下载链接…');
          stage('token');
          var b = bridge();
          if (b && b.onToken) { try { b.onToken(token); } catch (e) {} }
        },
        'error-callback': function (code) {
          // code 是 Turnstile 错误码（如 300030 网络异常、110200 域名未授权、600010 挑战超时），
          // 带上它才能在日志里定位是"环境被风控"还是"脚本/域名问题"
          fail(code ? ('验证组件报错（' + code + '），请关闭后重试') : '验证组件异常，请关闭后重试');
        },
        'timeout-callback': function () { fail('验证等待超时，请关闭后重试'); }
      });
    } catch (e) {
      fail('验证组件未能启动，请检查网络后重试');
    }
  }

  window.__mcdlBoot = function () {
    markLoaded();
    stage('script-loaded');
    // siteKey 请求在页面加载时就已经并行发出（不用等验证脚本下载完），
    // 脚本 onload 时多半已经拿到，直接 render，省掉一个串行往返
    siteKeyPromise.then(render);
  };

  // 两道看门狗：国内访问 challenges.cloudflare.com 经常"挂着不返回"，
  // 既不触发 onerror 也不 load，用户只能干等。
  //   ① 脚本 10 秒没加载完 → 报加载超时
  //   ② 脚本加载完但 6 秒内没成功 render → 报组件未就绪
  loadWatchdog = setTimeout(function () {
    if (!loaded) fail('验证组件加载超时，请检查网络后重试');
  }, 10000);

  function markLoaded() {
    if (loaded) return;
    loaded = true;
    clearTimeout(loadWatchdog);
    renderWatchdog = setTimeout(function () {
      if (!rendered) fail('验证组件未能启动，请检查网络后重试');
    }, 6000);
  }

  // siteKey 提前并行取：源站接口 3 秒没回就用内置兜底值，不让用户干等
  var siteKeyPromise = (function () {
    var p = fetch('/api/turnstile/config', { credentials: 'include' })
      .then(function (r) { return r.json(); })
      .then(function (cfg) { return (cfg && cfg.siteKey) || FALLBACK_SITEKEY; })
      .catch(function () { return FALLBACK_SITEKEY; });
    return Promise.race([p, new Promise(function (res) {
      setTimeout(function () { res(FALLBACK_SITEKEY); }, 3000);
    })]);
  })();

  var s = document.createElement('script');
  setTip('正在加载验证组件…');
  stage('script-start');
  s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=__mcdlBoot';
  // onload 先标"已加载"，避免脚本到手但回调没触发时被看门狗误判成加载失败；
  // 回调始终不来的话，renderWatchdog 会在 6 秒后报"组件未就绪"
  s.onload = function () { markLoaded(); };
  s.onerror = function () { fail('无法加载验证组件，请检查网络'); };
  document.head.appendChild(s);
})();
</script>
</body>
</html>
""".trimIndent()
