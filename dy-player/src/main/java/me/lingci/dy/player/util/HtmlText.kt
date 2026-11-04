package me.lingci.dy.player.util

/**
 * 解码标题/文件名里残留的 HTML 实体。
 *
 * 存量数据（旧版抓取与迁移写入的 `video_data.name`）里存在 `&#58;` `&#47;` 这类实体，
 * 旧版列表经 `Html.fromHtml` 显示，Compose 侧没有这一步，会直接看到 `&#58;`。
 *
 * 只做实体替换、不解析标签：标题里不应出现标签，也不希望把 HTML 样式带进 Compose 文本。
 * 不含 `&` 时原样返回，避免每次文本渲染都新建 StringBuilder。
 */
fun String.decodeHtmlEntities(): String {
    if (indexOf('&') < 0) return this
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val ch = this[i]
        if (ch != '&') {
            sb.append(ch)
            i++
            continue
        }
        val end = indexOf(';', i + 1)
        // 实体体最长 `#x10FFFF;`，超过这个长度基本可以断定不是实体，按普通字符处理
        if (end < 0 || end - i > 10) {
            sb.append(ch)
            i++
            continue
        }
        val decoded = decodeEntity(substring(i + 1, end))
        if (decoded == null) {
            sb.append(ch)
            i++
        } else {
            sb.append(decoded)
            i = end + 1
        }
    }
    return sb.toString()
}

private fun decodeEntity(body: String): String? {
    if (body.isEmpty()) return null
    if (body[0] == '#') {
        val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
            body.substring(2).toIntOrNull(16)
        } else {
            body.substring(1).toIntOrNull()
        }
        if (code == null || code <= 0 || code > 0x10FFFF) return null
        return String(Character.toChars(code))
    }
    return when (body.lowercase()) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> "\u00A0"
        else -> null
    }
}
