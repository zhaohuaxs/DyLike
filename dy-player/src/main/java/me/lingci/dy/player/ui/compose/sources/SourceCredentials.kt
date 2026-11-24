package me.lingci.dy.player.ui.compose.sources

/**
 * 来源表单的「输入框 → 落库值」归一规则。
 *
 * 单独抽成纯函数是因为两种协议的**空用户名语义相反**，而这里一旦走偏不会报错，
 * 只会表现为「匿名能连的库连不上了」：
 * - SMB：用户名留空 = 匿名/访客访问，必须原样留空。`SmbStorage` 只在 username 为空时才
 *   走匿名凭据；填了域名再兜底成 `guest` 会拼出 `DOMAIN\guest`，变成一次失败的显式登录。
 * - WebDAV：沿用旧实现的约定，用户名/密码不填就用 `guest`。
 */
object SourceCredentials {

    private const val GUEST = "guest"

    /** SMB 用户名：空则空；有域名时按存储层约定拼成 `DOMAIN\user`（存储层会再拆开） */
    fun smbUsername(username: String, domain: String): String {
        val user = username.trim()
        if (user.isBlank()) return ""
        val normalizedDomain = domain.trim()
        if (normalizedDomain.isBlank() || user.contains('\\') || user.contains(';')) return user
        return "$normalizedDomain\\$user"
    }

    /** SMB 密码：空就是空密码；非空密码原样保留，首尾空格可能是密码的一部分。 */
    fun smbPassword(password: String): String = password

    fun webdavUsername(username: String): String = username.trim().ifBlank { GUEST }

    fun webdavPassword(password: String): String = password.trim().ifBlank { GUEST }
}
