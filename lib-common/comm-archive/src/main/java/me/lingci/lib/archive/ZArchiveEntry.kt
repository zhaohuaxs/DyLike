package me.lingci.lib.archive

/**
 *   @author : happyc
 *   time    : 2025/12/14
 *   desc    : 压缩文件
 *   version : 1.0
 */
data class ZArchiveEntry(
    var name: String,
    var isDirectory: Boolean = false,
    var size: Long = 0,
    /** 在压缩包中的索引，-1 表示未知 */
    var index: Int = -1,
    /** 最后修改时间（epoch 毫秒），0 表示未知 */
    var lastModified: Long = 0,
    /** 该条目是否被加密 */
    var encrypted: Boolean = false,
    /** 原始路径名的十六进制（ISO-8859-1 字节），用于跨 TextFixer 版本稳定匹配；空串表示未知 */
    var rawName: String = ""
) {
}

