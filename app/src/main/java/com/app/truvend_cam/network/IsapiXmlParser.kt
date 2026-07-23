package com.app.truvend_cam.network

import com.app.truvend_cam.data.ChannelInfo
import com.app.truvend_cam.data.StreamType
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-function parsers for Hikvision ISAPI XML responses.
 * Uses javax.xml DOM so unit tests run on the JVM without Robolectric.
 */
object IsapiXmlParser {

    /**
     * Parse GET /ISAPI/Streaming/channels
     * Each StreamingChannel has id like 101, name, Video codec, Resolution, etc.
     */
    fun parseStreamingChannels(xml: String): List<ChannelInfo> {
        val doc = parseDocument(xml) ?: return emptyList()
        val nodes = doc.getElementsByTagName("*")
        val results = mutableListOf<ChannelInfo>()

        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            if (!localName(el).equals("StreamingChannel", ignoreCase = true)) continue

            val id = firstChildText(el, "id")?.toIntOrNull() ?: continue
            val name = firstChildText(el, "name")
                ?: firstChildText(el, "channelName")
                ?: "Channel ${RtspUrlBuilder.channelNumberOf(id)}"
            val videoInputId = firstChildText(el, "videoInputChannelID")?.toIntOrNull()
                ?: firstChildText(el, "videoInputChannelId")?.toIntOrNull()
                ?: RtspUrlBuilder.channelNumberOf(id)
            val codec = firstChildText(el, "videoCodecType")
                ?: firstChildText(el, "codec")
            val width = firstChildText(el, "videoResolutionWidth")?.toIntOrNull()
                ?: firstChildText(el, "width")?.toIntOrNull()
            val height = firstChildText(el, "videoResolutionHeight")?.toIntOrNull()
                ?: firstChildText(el, "height")?.toIntOrNull()

            val streamCode = RtspUrlBuilder.streamTypeCodeOf(id)
            val streamType = if (streamCode == 1) StreamType.MAIN else StreamType.SUB

            results += ChannelInfo(
                streamingChannelId = id,
                name = name,
                videoInputChannelId = videoInputId,
                streamType = streamType,
                codec = codec,
                width = width,
                height = height,
            )
        }
        return results.sortedWith(compareBy({ it.videoInputChannelId }, { it.streamType.code }))
    }

    /**
     * Parse GET /ISAPI/System/Video/inputs/channels
     */
    fun parseVideoInputChannels(xml: String): List<Int> {
        val doc = parseDocument(xml) ?: return emptyList()
        val nodes = doc.getElementsByTagName("*")
        val ids = mutableListOf<Int>()

        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            val local = localName(el)
            if (!local.equals("VideoInputChannel", ignoreCase = true) &&
                !local.equals("InputProxyChannel", ignoreCase = true)
            ) {
                continue
            }
            firstChildText(el, "id")?.toIntOrNull()?.let { ids += it }
        }
        return ids.distinct().sorted()
    }

    fun expandInputChannelsToStreaming(inputIds: List<Int>): List<ChannelInfo> {
        val out = mutableListOf<ChannelInfo>()
        for (inputId in inputIds) {
            for (stream in listOf(StreamType.MAIN, StreamType.SUB)) {
                val sid = RtspUrlBuilder.streamingChannelId(inputId, stream.code)
                out += ChannelInfo(
                    streamingChannelId = sid,
                    name = "Channel $inputId",
                    videoInputChannelId = inputId,
                    streamType = stream,
                )
            }
        }
        return out
    }

    /** Probe fallback: channels 1..16 main+sub. */
    fun probeChannelList(maxChannels: Int = 16): List<ChannelInfo> =
        expandInputChannelsToStreaming((1..maxChannels).toList())

    private fun parseDocument(xml: String) = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    } catch (_: Exception) {
        null
    }

    private fun localName(el: Element): String {
        val local = el.localName
        if (!local.isNullOrBlank()) return local
        val name = el.tagName
        val idx = name.indexOf(':')
        return if (idx >= 0) name.substring(idx + 1) else name
    }

    /** Direct-child text only (avoids grabbing nested names from Video blocks incorrectly for id). */
    private fun firstChildText(parent: Element, tag: String): String? {
        val children: NodeList = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType != Node.ELEMENT_NODE) continue
            val child = n as Element
            if (localName(child).equals(tag, ignoreCase = true)) {
                return child.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        // Fallback: deep search for codec/resolution nested under Video
        val deep = parent.getElementsByTagName("*")
        for (i in 0 until deep.length) {
            val n = deep.item(i)
            if (n.nodeType != Node.ELEMENT_NODE) continue
            val el = n as Element
            if (localName(el).equals(tag, ignoreCase = true)) {
                return el.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
