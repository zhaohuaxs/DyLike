package me.lingci.lib.base.storage.smb

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import java.util.Properties

/**
 * SMB 客户端统一配置工厂。
 *
 * 浏览侧 SmbStorage 与播放侧 SmbAuthManager 共用同一份 jcifs 属性，
 * 避免两侧超时/DFS 行为不一致（阶段 A1/A2）。
 */
object SmbClientConfig {

    /**
     * 集中产出 jcifs 属性。
     * - 超时与浏览侧原配置保持一致（responseTimeout 30s / soTimeout 35s / maxConnections 4）
     * - dfs.disabled=true：Nova 实测为 Win10/11 微软账号共享可连的必要项（阶段 A2 实测结论）
     */
    fun properties(): Properties = Properties().apply {
        setProperty("jcifs.smb.client.responseTimeout", "30000")
        setProperty("jcifs.smb.client.soTimeout", "35000")
        setProperty("jcifs.smb.client.maxConnections", "4")
        setProperty("jcifs.smb.client.dfs.disabled", "true")
    }

    /** 基于统一属性的基础上下文，凭据上下文由调用方 withCredentials/withAnonymousCredentials 派生。 */
    fun baseContext(): CIFSContext = BaseContext(PropertyConfiguration(properties()))
}