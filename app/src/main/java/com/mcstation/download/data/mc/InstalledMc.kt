package com.mcstation.download.data.mc

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * 识别手机上已安装的 Minecraft 属于哪个渠道（靠签名证书指纹判断，不依赖包名之外的元数据）。
 *
 * 三个渠道的证书指纹来自实测：
 *  - 官方包        CN=Mojang AB            （Google Play Source Stamp 同在）
 *  - 标准去验证包  CN=jimohanyin / Starmoon Studio
 *  - 第三方去验证包（MCPELIFE）android@android.com（AOSP 公开测试密钥）
 *
 * 注意：包名相同但签名不同的包**无法共存**，所以这里最多只有一个结果。
 */
object InstalledMc {

    const val PACKAGE = "com.mojang.minecraftpe"

    /** 官方签名证书 SHA-256（小写十六进制） */
    private const val SHA_MOJANG = "31be40096f931cd7f11d5e262d2b2c437c44385fb4ecbc1013d95a7435816f9c"

    /** 小布「标准去验证包」签名证书 SHA-256（CN=jimohanyin / Starmoon Studio） */
    private const val SHA_XIAOBU = "17df96756617bb66a4e8ae9a8a9cc69aac96744011b92bd3e43fa5cf33ee3f6d"

    /** zihao 上游「标准去验证包」签名证书 SHA-256（CN=zihao_il；2026-11-01 换源后的新签名） */
    private const val SHA_ZIHAO_UPSTREAM = "fc44329409b833243da61c1660e5094d8bd6aa301c75c1f8185e3365e48b9933"

    /** MCPELIFE 签名证书 SHA-256（AOSP 测试密钥） */
    private const val SHA_AOSP = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"

    /**
     * @param channel 识别出的渠道；null 表示已装 MC 但签名不属于上面三类（其他改包）
     * @param versionName 已装版本号，如 "1.26.51.1"
     */
    data class Installed(
        val channel: DownloadChannel?,
        val versionName: String?,
        /**
         * 具体签名来源："official" / "xiaobu" / "zihao" / "aosp"。
         * 两个标准去验证包渠道签名不同（渠道已各自独立，此字段留作 finer 粒度判断）。
         */
        val sigKind: String? = null,
    )

    /** 未安装返回 null；需要 Android 11+ 的 `<queries>` 声明才能查到这个包 */
    fun detect(context: Context): Installed? {
        val pm = context.packageManager
        val info = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
            }
        } catch (e: Exception) {
            return null
        }

        val digests = certDigests(info)
        val channel: DownloadChannel?
        val sigKind: String?
        when {
            SHA_MOJANG in digests -> { channel = DownloadChannel.OFFICIAL; sigKind = "official" }
            SHA_XIAOBU in digests -> { channel = DownloadChannel.DEV_VERIFIED; sigKind = "xiaobu" }
            SHA_ZIHAO_UPSTREAM in digests -> { channel = DownloadChannel.ZIHAO; sigKind = "zihao" }
            SHA_AOSP in digests -> { channel = DownloadChannel.MCPELIFE; sigKind = "aosp" }
            else -> { channel = null; sigKind = null }
        }
        return Installed(channel, info.versionName, sigKind)
    }

    /** 取 APK 全部签名证书的 SHA-256（多签名时逐个比对） */
    private fun certDigests(info: android.content.pm.PackageInfo): Set<String> {
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptySet()
        }
        return sigs.mapNotNull { sig ->
            try {
                MessageDigest.getInstance("SHA-256")
                    .digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                null
            }
        }.toSet()
    }
}
