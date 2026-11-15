package me.lingci.lib.player.exo.smb

import jcifs.CIFSContext
import jcifs.smb.NtlmPasswordAuthenticator
import me.lingci.lib.base.storage.smb.SmbAuthToken
import me.lingci.lib.base.storage.smb.SmbClientConfig

object SmbAuthManager {

    // 基础上下文：与浏览侧 SmbStorage 共用同一份配置（超时/DFS，阶段 A1/A2），
    // 替换原 SingletonContext 默认配置；凭据上下文从它派生，传输层共享语义不变。
    private val baseContext = SmbClientConfig.baseContext()

    /**
     * 生成认证上下文
     * @param username 用户名，匿名登录传 null
     * @param password 密码，匿名登录传 null
     * @param domain 域名/工作组，通常传 null 或 "WORKGROUP"
     */
    fun getContext(username: String?, password: String?, domain: String? = null): CIFSContext {
        val credentials = SmbAuthToken.normalize(username, password, domain)
        return if (credentials.username.isNullOrBlank()) {
            // 匿名登录
            baseContext.withAnonymousCredentials()
        } else {
            // 账号密码登录
            val auth = NtlmPasswordAuthenticator(
                credentials.domain,
                credentials.username,
                credentials.password.orEmpty()
            )
            baseContext.withCredentials(auth)
        }
    }

}
