package me.lingci.dy.player.ui.compose.sources

/**
 * 来源表单的「输入框 → 落库值」归一规则。
 *
 * 单独抽成纯函数是因为两种协议的**空用户名语义相反**，而这里一旦走偏不会报错，
 * 只会表现为「匿名能连的库连不上了」：
 * - SMB：用户名留空 = 匿名访问，必须原样留空。Windows Guest 模式显式使用 `Guest` 和空密码，
 *   不能给空用户名拼域名后兜底成 `guest`，否则会变成 `DOMAIN\guest` 的失败登录。
 * - WebDAV：沿用旧实现的约定，用户名/密码不填就用 `guest`。
 */
object SourceCredentials {

    private const val GUEST = "guest"
    private const val WINDOWS_GUEST = "Guest"

    fun smbWindowsGuestUsername(): String = WINDOWS_GUEST

    fun isSmbWindowsGuest(username: String, password: String): Boolean =
        username.trim().equals(WINDOWS_GUEST, ignoreCase = true) && password.isEmpty()

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
