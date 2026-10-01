package io.github.gua123.mediagate.data.storage.webdav

import org.w3c.dom.Element
import org.w3c.dom.Node
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * 207 multistatus 的解析（R2 列目录）。
 *
 * **命名空间无关**：用 `isNamespaceAware = false` 建 DOM，然后一律按「local name」匹配
 * （`D:getcontentlength` / `d:getcontentlength` / `getcontentlength` 一视同仁）。服务器把
 * 前缀写成什么、甚至不写前缀，都不影响解析——这是 WebDAV 互操作里最常见的坑之一。
 *
 * 另外做了两件防御：只认 `propstat` 里 status 为 200 的那份属性（404 的 propstat 是服务器在说
 * 「这个属性我没有」），以及禁掉 DTD / 外部实体（XXE）。
 */
internal data class DavEntry(
    /** 服务器原样返回的 href（未解码，可能是绝对 URL、绝对路径或相对路径）。 */
    val href: String,
    val isDirectory: Boolean,
    /** 字节数；目录或服务器没给为 -1。 */
    val size: Long,
    /** 最后修改时间（Unix 毫秒）；未知为 0。 */
    val mtime: Long,
    val etag: String?,
    val mimeType: String?,
)

/** 节点 local name（小写）：去掉 `D:` 这类前缀。 */
internal fun localNameOf(node: Node): String =
    (node.nodeName ?: "").substringAfterLast(':').lowercase(Locale.US)

/**
 * 解析 PROPFIND 响应体。
 *
 * @throws StorageException.Unknown 根节点不是 multistatus，或 XML 本身就坏了。
 */
internal fun parseMultistatus(xml: ByteArray): List<DavEntry> {
    if (xml.isEmpty()) return emptyList()
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        isCoalescing = true
        setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val document = try {
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    } catch (t: Throwable) {
        throw StorageException.Unknown("PROPFIND 响应不是合法 XML", t)
    }
    val root = document.documentElement ?: return emptyList()
    if (localNameOf(root) != "multistatus") {
        throw StorageException.Unknown("PROPFIND 响应根节点不是 multistatus：${root.nodeName}")
    }
    val entries = ArrayList<DavEntry>()
    for (response in root.childElements().filter { localNameOf(it) == "response" }) {
        val href = response.childElements().firstOrNull { localNameOf(it) == "href" }?.textContent?.trim()
        if (href.isNullOrEmpty()) continue
        var isDirectory = false
        var size = -1L
        var mtime = 0L
        var etag: String? = null
        var mimeType: String? = null
        for (propstat in response.childElements().filter { localNameOf(it) == "propstat" }) {
            val status = propstat.childElements().firstOrNull { localNameOf(it) == "status" }?.textContent.orEmpty()
            if (status.isNotBlank() && !status.contains(" 200 ")) continue // 「我没有这个属性」的 propstat
            val prop = propstat.childElements().firstOrNull { localNameOf(it) == "prop" } ?: continue
            for (child in prop.childElements()) {
                when (localNameOf(child)) {
                    "resourcetype" -> isDirectory = child.childElements().any { localNameOf(it) == "collection" }
                    "getcontentlength" -> size = child.textContent?.trim()?.toLongOrNull() ?: -1L
                    "getlastmodified" -> mtime = parseHttpDate(child.textContent)
                    "getetag" -> etag = child.textContent?.trim()?.ifEmpty { null }
                    "getcontenttype" -> mimeType = child.textContent?.trim()?.ifEmpty { null }
                }
            }
        }
        entries += DavEntry(
            href = href,
            isDirectory = isDirectory,
            size = if (isDirectory) -1L else size, // 目录恒 -1（RemoteEntry 的约定：不要用 0 冒充未知）
            mtime = mtime,
            etag = etag,
            mimeType = mimeType,
        )
    }
    return entries
}

private fun Element.childElements(): List<Element> {
    val nodes = childNodes
    val out = ArrayList<Element>(nodes.length)
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element) out += node
    }
    return out
}

private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: ParserConfigurationException) {
        // Android 与桌面 JVM 支持的 feature 集合不完全一样，不支持就跳过
    }
}
