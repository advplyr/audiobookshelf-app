package com.audiobookshelf.app.dlna

import android.util.Xml
import java.io.StringWriter

object DlnaMetadataBuilder {

    fun buildAudioMetadata(
        title: String,
        artist: String?,
        album: String?,
        albumArtUrl: String?,
        mediaUrl: String,
        mimeType: String,
        durationSeconds: Long?
    ): String {
        val writer = StringWriter()
        val serializer = Xml.newSerializer()
        serializer.setOutput(writer)

        serializer.startDocument("UTF-8", true)

        serializer.startTag("", "DIDL-Lite")
        serializer.attribute("", "xmlns", "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/")
        serializer.attribute("", "xmlns:dc", "http://purl.org/dc/elements/1.1/")
        serializer.attribute("", "xmlns:upnp", "urn:schemas-upnp-org:metadata-1-0/upnp/")

        serializer.startTag("", "item")
        serializer.attribute("", "id", "1")
        serializer.attribute("", "parentID", "0")
        serializer.attribute("", "restricted", "1")

        serializer.startTag("", "dc:title")
        serializer.text(title)
        serializer.endTag("", "dc:title")

        if (artist != null) {
            serializer.startTag("", "dc:creator")
            serializer.text(artist)
            serializer.endTag("", "dc:creator")

            serializer.startTag("", "upnp:artist")
            serializer.text(artist)
            serializer.endTag("", "upnp:artist")
        }

        if (album != null) {
            serializer.startTag("", "upnp:album")
            serializer.text(album)
            serializer.endTag("", "upnp:album")
        }

        serializer.startTag("", "upnp:class")
        serializer.text("object.item.audioItem.musicTrack")
        serializer.endTag("", "upnp:class")

        if (albumArtUrl != null) {
            serializer.startTag("", "upnp:albumArtURI")
            serializer.text(albumArtUrl)
            serializer.endTag("", "upnp:albumArtURI")
        }

        serializer.startTag("", "res")
        serializer.attribute("", "protocolInfo", "http-get:*:$mimeType:*")
        if (durationSeconds != null) {
            serializer.attribute("", "duration", formatDuration(durationSeconds))
        }
        serializer.text(mediaUrl)
        serializer.endTag("", "res")

        serializer.endTag("", "item")
        serializer.endTag("", "DIDL-Lite")
        serializer.endDocument()

        return writer.toString()
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%d:%02d:%02d.000", hours, minutes, secs)
    }
}
