package me.lingci.lib.dm.view.util

import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.decodeDm
import me.lingci.lib.base.util.encodeDm
import me.lingci.lib.base.util.replaceDm
import me.lingci.lib.dm.view.common.DmInitializer
import me.lingci.lib.dm.view.entity.DmTrack
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.FileReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

@Suppress("unused", "MemberVisibilityCanBePrivate")
object XmlMerger {

    fun mergeXmlParts(
        inputFiles: List<DmTrack>,
        outputFile: File,
        onLog: ((name:String, message: String) -> Unit)? = null
    ) {
        mergeXmlParts(
            inputFiles,
            outputFile.outputStream(),
            onLog
        )
    }

    fun mergeXmlParts(
        inputFiles: List<DmTrack>,
        outputStream: OutputStream,
        onLog: ((name:String, message: String) -> Unit)? = null
    ) {
        mergeXmlParts(
            inputFiles,
            outputStream,
            DmInitializer.DANMAKU_TAG_I,
            DmInitializer.DANMAKU_TAG_D,
            onLog
        )
    }

    fun mergeXmlParts(
        inputFiles: List<DmTrack>,
        outputFile: File,
        parentTag: String,
        childTag: String,
        onLog: ((name:String, message: String) -> Unit)? = null
    ) {
        mergeXmlParts(inputFiles, outputFile.outputStream(), parentTag, childTag, onLog)
    }

    fun mergeXmlParts(
        inputFiles: List<DmTrack>,
        outputStream: OutputStream,
        parentTag: String,
        childTag: String,
        onLog: ((name:String, message: String) -> Unit)? = null
    ) {
        // 创建解析器工厂并配置
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }

        // 使用use自动管理资源
        outputStream.use { stream ->
            // 配置XML序列化器
            val serializer = factory.newSerializer().apply {
                setOutput(OutputStreamWriter(stream, StandardCharsets.UTF_8))
                startDocument(StandardCharsets.UTF_8.name().lowercase(), null)
            }

            // 根节点
            serializer.text("\n")
            serializer.startTag(null, parentTag)
            serializer.text("\n")

            // 处理每个输入文件
            inputFiles.forEach { track ->
                if (track.isArchiveTrack()) {
                    processZipFile(track, factory, serializer, parentTag, childTag, onLog)
                } else {
                    processSingleFile(File(track.path), factory, serializer, parentTag, childTag, track.offset, onLog)
                }
            }

            // 完成文档
            serializer.endTag(null, parentTag)
            serializer.endDocument()
        }
    }

    /**
     * 处理单个XML文件，提取指定节点并写入序列化器
     */
    private fun processSingleFile(
        file: File,
        factory: XmlPullParserFactory,
        serializer: XmlSerializer,
        parentTag: String,
        childTag: String,
        offset: Long = 0,
        onLog: ((name:String, message: String) -> Unit)? = null
    ) {
        // 检查文件是否存在且可读
        if (!file.exists() || !file.canRead()) {
            onLog?.invoke(file.name, "文件不存在或无法读取，已跳过")
            return
        }

        FileReader(file).use { reader ->
            val parser = factory.newPullParser().apply {
                setInput(reader)
            }

            var eventType = parser.eventType
            var inParentTag = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name ?: ""

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals(parentTag, ignoreCase = true)) {
                            inParentTag = true
                        }
                        // 只处理父节点内的目标子节点
                        else if (inParentTag && tagName.equals(childTag, ignoreCase = true)) {
                            copyNode(parser, serializer, childTag, offset)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (tagName.equals(parentTag, ignoreCase = true)) {
                            inParentTag = false
                        }
                    }
                }

                try {
                    eventType = parser.next()
                } catch (e: Exception) {
                    Log.d(this, "merge failed", file.name, parser.text, e.message)
                    try {
                        eventType = parser.next()
                    } catch (ex: Exception) {
                        Log.d(this, "merge failed", file.name, parser.text, e)
                        onLog?.invoke(file.name, "合并出错")
                        break
                    }
                }
            }
        }
        onLog?.invoke(file.name, "")
    }

    /**
     * 处理压缩包单个XML文件，提取指定节点并写入序列化器
     */
    private fun processZipFile(
        track: DmTrack,
        factory: XmlPullParserFactory,
        serializer: XmlSerializer,
        parentTag: String,
        childTag: String,
        onLog: ((name:String, message: String) -> Unit)? = null
    ) {
        val file = File(track.path)
        // 检查文件是否存在且可读
        if (!file.exists() || !file.canRead()) {
            onLog?.invoke(file.name, "文件不存在或无法读取，已跳过")
            return
        }
        // 旧数据 archiveEntryName 为空时回落到 title（旧版本 title 即条目名）
        val entryName = track.resolvedArchiveEntryName()
        if (entryName.isBlank()) {
            onLog?.invoke(track.title, "压缩包缺少包内条目信息，已跳过")
            return
        }
        val openResult = ZipXmlLoader.loadXmlFromZipResult(
            FileEntity(
                name = entryName,
                title = track.title,
                path = track.path,
                mimeType = track.normalizedMineType()
            ),
            track.password,
            track.archiveEntryRaw,
            track.archiveEntryIndex,
            track.title
        )

        if (openResult.state != ZipXmlLoader.OpenResultState.SUCCESS || openResult.stream == null) {
            val message = when (openResult.state) {
                ZipXmlLoader.OpenResultState.WRONG_PASSWORD -> "密码错误，已跳过"
                ZipXmlLoader.OpenResultState.UNSUPPORTED_METHOD -> "压缩方法不支持，已跳过"
                ZipXmlLoader.OpenResultState.ENTRY_NOT_FOUND -> "压缩包条目不存在，已跳过"
                ZipXmlLoader.OpenResultState.ERROR -> "压缩包读取失败，已跳过"
                ZipXmlLoader.OpenResultState.SUCCESS -> ""
            }
            if (message.isNotBlank()) {
                onLog?.invoke(track.title, message)
            }
            return
        }

        openResult.stream.use { stream ->
            val parser = factory.newPullParser().apply {
                setInput(stream, "utf-8")
            }

            var eventType = parser.eventType
            var inParentTag = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name ?: ""

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals(parentTag, ignoreCase = true)) {
                            inParentTag = true
                        }
                        // 只处理父节点内的目标子节点
                        else if (inParentTag && tagName.equals(childTag, ignoreCase = true)) {
                            copyNode(parser, serializer, childTag, track.offset)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (tagName.equals(parentTag, ignoreCase = true)) {
                            inParentTag = false
                        }
                    }
                }

                try {
                    eventType = parser.next()
                } catch (e: Exception) {
                    Log.d(this, "merge failed", track.title, parser.text, e.message)
                    try {
                        eventType = parser.next()
                    } catch (ex: Exception) {
                        Log.d(this, "merge failed", track.title, parser.text, e)
                        onLog?.invoke(track.title, "合并出错")
                        break
                    }
                }
            }
        }
        onLog?.invoke(track.title, "")
    }

    /**
     * 复制节点及其所有内容（属性、文本）
     */
    private fun copyNode(
        parser: XmlPullParser,
        serializer: XmlSerializer,
        tagName: String,
        offset: Long = 0
    ) {
        // 开始复制当前节点
        serializer.text("\t")
        serializer.startTag(null, tagName)

        // 复制所有属性
        repeat(parser.attributeCount) { i ->
            if (DmInitializer.DANMAKU_ATTR_P.equals(parser.getAttributeName(i), ignoreCase = true)) {
                var style = parser.getAttributeValue(i)
                // 偏移处理
                if (style.isNotBlank() && offset != 0L) {
                    style = "${style.substringBefore(",").toDouble() + (offset / 1000f)},${style.substringAfter(",")}"
                }
                serializer.attribute(
                    parser.getAttributeNamespace(i),
                    parser.getAttributeName(i),
                    style
                )
            } else {
                serializer.attribute(
                    parser.getAttributeNamespace(i),
                    parser.getAttributeName(i),
                    parser.getAttributeValue(i)
                )
            }
        }

        // 处理节点内部内容
        var innerEventType = parser.next()
        while (!(innerEventType == XmlPullParser.END_TAG
                    && parser.name.equals(tagName, ignoreCase = true))
        ) {
            when (innerEventType) {
                XmlPullParser.TEXT -> {
                    val text = parser.text.replace("\t", "").replace("\n", "").trim().decodeDm()
                    serializer.text(text.replaceDm().encodeDm())
                }
            }
            innerEventType = parser.next()
        }

        // 结束当前节点复制
        serializer.endTag(null, tagName)
        serializer.text("\n")
    }

}
