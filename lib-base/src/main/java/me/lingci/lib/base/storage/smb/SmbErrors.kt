package me.lingci.lib.base.storage.smb

import android.util.Log
import jcifs.smb.NtStatus
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * SMB 错误分类（阶段 A3）。
 *
 * 浏览层原来把异常一律吞成空列表/false，上层无法区分认证失败/网络断开/共享不存在。
 * 这里提供异常 → 错误类型的映射与面向用户的中文文案；日志只输出定位信息，禁止输出凭据。
 */
enum class SmbErrorType {
    AUTH,
    NETWORK,
    NOT_FOUND,
    UNKNOWN
}

/** 测试连接结果：success 为 true 时 errorType 为 null。 */
data class SmbTestResult(
    val success: Boolean,
    val errorType: SmbErrorType? = null
)

object SmbErrors {

    private const val TAG = "SmbErrors"

    /** 沿 cause 链分类；认证优先（SmbAuthException 本身也是 SmbException）。 */
    fun classify(error: Throwable?): SmbErrorType {
        var current = error
        var depth = 0
        while (current != null && depth < 8) {
            when {
                current is SmbAuthException -> return SmbErrorType.AUTH
                current is UnknownHostException ||
                    current is ConnectException ||
                    current is SocketTimeoutException ||
                    current is SocketException -> return SmbErrorType.NETWORK
                current is SmbException -> {
                    when (current.ntStatus) {
                        NtStatus.NT_STATUS_LOGON_FAILURE,
                        NtStatus.NT_STATUS_ACCESS_DENIED,
                        NtStatus.NT_STATUS_NETWORK_ACCESS_DENIED -> return SmbErrorType.AUTH
                        NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND,
                        NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND,
                        NtStatus.NT_STATUS_BAD_NETWORK_NAME -> return SmbErrorType.NOT_FOUND
                    }
                }
            }
            current = current.cause
            depth++
        }
        return SmbErrorType.UNKNOWN
    }

    /** 面向用户的失败文案，沿用现有中文提示风格。 */
    fun userMessage(type: SmbErrorType): String = when (type) {
        SmbErrorType.AUTH -> "认证失败，请检查用户名/密码/域"
        SmbErrorType.NETWORK -> "网络不通或超时，请检查服务器地址与网络"
        SmbErrorType.NOT_FOUND -> "共享或路径不存在，请检查共享名称"
        SmbErrorType.UNKNOWN -> "连接失败，账号正确时请检查域/工作组"
    }

    /** 结构化错误日志：只输出 server/share/path 与错误类型，不输出 token/密码。 */
    fun log(operation: String, server: String, share: String, path: String, error: Throwable?) {
        Log.w(
            TAG,
            "$operation failed: server=$server share=$share path=$path type=${classify(error)}",
            error
        )
    }
}