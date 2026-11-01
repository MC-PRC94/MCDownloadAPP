package com.mcstation.download.data.mc

/**
 * Minecraft 安装包下载渠道。
 *
 * 用户可选的是两个「标准去验证包」（2026-11-01，用户指定命名）：
 * - 标准去验证包1：小布（mcdl.xbu.hk），单 APK，免谷歌验证；
 * - 标准去验证包2：zihao「MC版本库」（bbk.endyun.ltd），单 APK，免验证直链。
 *
 * 两者包内容基本一致但**签名不同**（小布 CN=jimohanyin / zihao CN=zihao_il），
 * 安装时**互相不能覆盖**，下载前必须让用户明确选择，并在已装渠道与所选渠道不同时提示备份。
 *
 * 【文案】[title] / [subtitle] 由用户指定，不要随意改动。
 */
enum class DownloadChannel(
    /** 弹窗中显示的名称 */
    val title: String,
    /** 名称下方的小字说明 */
    val subtitle: String,
    /** 包格式：true = 单 APK（可直接安装），false = APKS 拆分容器（需拆分安装） */
    val singleApk: Boolean,
    /** 是否已支持下载（false 时选项置灰） */
    val available: Boolean = true,
    /**
     * 是否出现在「请选择下载渠道」弹窗里。
     * 官方包 / 第三方包不提供选择，枚举保留——用于识别手机里已装的包
     * （跨渠道时仍会提示备份）。
     */
    val selectable: Boolean = true,
) {
    /** 标准去验证包1：小布（mcdl.xbu.hk）提供，单 APK，免谷歌验证 */
    DEV_VERIFIED(
        title = "标准去验证包1",
        subtitle = "由mcdl.xbu.hk提供 需完成一次人机验证",
        singleApk = true,
    ),

    /** 标准去验证包2：zihao「MC版本库」提供，单 APK，免验证直链（支持多线程/断点） */
    ZIHAO(
        title = "标准去验证包2",
        subtitle = "由bbk.endyun.ltd提供",
        singleApk = true,
    ),

    /** 官方包：mcdl.xbu.hk 提供，APKS 五件套，需要正版与谷歌框架（不提供选择，仅用于识别已装包） */
    OFFICIAL(
        title = "官方包",
        subtitle = "适合有正版的玩家 由mcdl.xbu.hk提供",
        singleApk = false,
        available = false,
        selectable = false,
    ),

    /** MCPELIFE 包（界面显示为「第三方去验证包」）：mcpelife.com 提供，单 APK，含注入文件。
     *  2026-11-01 用户要求下架：不再出现在渠道选择里（selectable=false），
     *  枚举保留——已装 MCPELIFE 包的设备仍靠它识别渠道并提示备份。 */
    MCPELIFE(
        title = "第三方去验证包",
        subtitle = "不推荐 有部分注入文件 由mcpelife.com提供",
        singleApk = true,
        selectable = false,
    );

    /** 弹窗标题（用户指定） */
    companion object {
        const val DIALOG_TITLE = "请选择下载渠道"
        /** 已安装标记：在选项后追加（用户未指定文案，可随时修改） */
        const val INSTALLED_BADGE = "已安装"
        /** 暂未开放的标记 */
        const val UNAVAILABLE_BADGE = "暂未开放"
        /** 默认渠道：标准去验证包1（推荐项） */
        val DEFAULT = DEV_VERIFIED
    }
}
