package com.moyue.reader.parser.epub

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class EpubSpineItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String,
)

data class EpubPackage(
    val title: String,
    val author: String?,
    val packageDirectory: File,
    val spine: List<EpubSpineItem>,
    val cover: EpubSpineItem?,
    val chapterTitles: Map<String, String> = emptyMap(),
)

class EpubPackageParser(
    private val factoryProvider: () -> DocumentBuilderFactory = DocumentBuilderFactory::newInstance,
) {
    fun parse(root: File): EpubPackage {
        val container = parseXml(root.resolve("META-INF/container.xml"))
        val rootfile = container.elements("rootfile").firstOrNull()
            ?.getAttribute("full-path")?.takeIf(String::isNotBlank)
            ?: error("EPUB 缺少 OPF 路径")
        val opfFile = safeResolve(root, rootfile)
        val document = parseXml(opfFile)
        val manifest = document.elements("item").associate { element ->
            val item = EpubSpineItem(
                id = element.getAttribute("id"),
                href = element.getAttribute("href").substringBefore('#'),
                mediaType = element.getAttribute("media-type"),
                properties = element.getAttribute("properties"),
            )
            item.id to item
        }
        val spine = document.elements("itemref").mapNotNull { manifest[it.getAttribute("idref")] }
        require(spine.isNotEmpty()) { "EPUB 没有可阅读章节" }
        val coverId = document.elements("meta")
            .firstOrNull { it.getAttribute("name").equals("cover", true) }
            ?.getAttribute("content")
        val directory = requireNotNull(opfFile.parentFile)
        val titles = linkedMapOf<String, String>()
        manifest.values.filter { it.mediaType == "application/x-dtbncx+xml" || "nav" in it.properties.split(' ') }.forEach { item ->
            val file = safeResolve(directory, item.href)
            val navigation = runCatching { parseXml(file) }.getOrNull() ?: return@forEach
            navigation.elements("navPoint").forEach { point ->
                val content = point.getElementsByTagNameNS("*", "content").item(0) as? Element
                val label = point.getElementsByTagNameNS("*", "text").item(0)?.textContent?.trim()
                val href = content?.getAttribute("src")?.substringBefore('#')
                if (!href.isNullOrBlank() && !label.isNullOrBlank()) {
                    val target = safeResolve(directory, directory.toPath().relativize(requireNotNull(file.parentFile).toPath()).resolve(href).toString())
                    titles.putIfAbsent(target.canonicalPath, label)
                }
            }
            navigation.elements("nav").filter { it.getAttribute("epub:type") == "toc" || it.getAttribute("role") == "doc-toc" }.forEach { nav ->
                val links = nav.getElementsByTagNameNS("*", "a")
                for (i in 0 until links.length) {
                    val a = links.item(i) as? Element ?: continue
                    val href = a.getAttribute("href").substringBefore('#')
                    if (href.isNotBlank()) {
                        val target = safeResolve(directory, directory.toPath().relativize(requireNotNull(file.parentFile).toPath()).resolve(href).toString())
                        titles.putIfAbsent(target.canonicalPath, a.textContent.trim())
                    }
                }
            }
        }
        return EpubPackage(
            title = document.elements("title").firstOrNull()?.textContent?.trim().orEmpty().ifBlank { "未命名书籍" },
            author = document.elements("creator").firstOrNull()?.textContent?.trim()?.takeIf(String::isNotBlank),
            packageDirectory = requireNotNull(opfFile.parentFile),
            spine = spine,
            chapterTitles = titles,
            cover = manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }
                ?: coverId?.let(manifest::get),
        )
    }

    fun safeResolve(parent: File, relative: String): File {
        val root = parent.canonicalFile
        val file = File(root, relative.replace("%20", " ")).canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator)) { "EPUB 引用了不安全路径" }
        return file
    }

    private fun parseXml(file: File): Document {
        require(file.isFile) { "EPUB 缺少 ${file.name}" }
        val factory = factoryProvider().apply {
            isNamespaceAware = true
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return file.inputStream().use(builder::parse)
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun Document.elements(localName: String): List<Element> {
        val namespaced = getElementsByTagNameNS("*", localName)
        val nodes = if (namespaced.length > 0) namespaced else getElementsByTagName(localName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
}
