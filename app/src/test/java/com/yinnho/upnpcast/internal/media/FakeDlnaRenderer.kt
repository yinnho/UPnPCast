package com.yinnho.upnpcast.internal.media

import fi.iki.elonen.NanoHTTPD

/**
 * Minimal in-process DLNA renderer for unit tests: serves a device
 * description document and AVTransport / RenderingControl SOAP endpoints,
 * recording every control call.
 */
class FakeDlnaRenderer : NanoHTTPD("127.0.0.1", 0) {

    data class SoapCall(val action: String, val body: String)

    val soapCalls = mutableListOf<SoapCall>()

    var transportState = "PLAYING"
    var relTime = "00:00:10"
    var trackDuration = "00:10:00"
    var volume = "30"
    var mute = "0"
    var failControlRequests = false

    val baseUrl: String get() = "http://127.0.0.1:$listeningPort"
    val descriptionUrl: String get() = "$baseUrl/dev/desc"

    val descriptionXml: String
        get() = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
                <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>Test Renderer</friendlyName>
                    <manufacturer>UnitTest Corp</manufacturer>
                    <modelName>TestModel 1000</modelName>
                    <serviceList>
                        <service>
                            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                            <serviceId>AVTransport</serviceId>
                            <controlURL>/control/AVTransport</controlURL>
                            <eventSubURL>/event/AVTransport</eventSubURL>
                            <SCPDURL>/scpd/AVTransport.xml</SCPDURL>
                        </service>
                        <service>
                            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                            <serviceId>RenderingControl</serviceId>
                            <controlURL>/control/RenderingControl</controlURL>
                            <eventSubURL>/event/RenderingControl</eventSubURL>
                            <SCPDURL>/scpd/RenderingControl.xml</SCPDURL>
                        </service>
                    </serviceList>
                </device>
            </root>
        """.trimIndent()

    fun startServer(): FakeDlnaRenderer {
        start(SOCKET_READ_TIMEOUT, false)
        return this
    }

    fun controlUrls(): Pair<String, String> = "/control/AVTransport" to "/control/RenderingControl"

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/dev/desc" -> newFixedLengthResponse(Response.Status.OK, "text/xml", descriptionXml)
            "/control/AVTransport", "/control/RenderingControl" -> serveControl(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    }

    private fun serveControl(session: IHTTPSession): Response {
        if (failControlRequests) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/xml", "boom")
        }

        val body = readBody(session)
        val action = ACTION_REGEX.find(body)?.groupValues?.get(1) ?: "Unknown"
        soapCalls.add(SoapCall(action, body))

        val inner = when (action) {
            "GetTransportInfo" -> "<CurrentTransportState>$transportState</CurrentTransportState>"
            "GetPositionInfo" -> "<RelTime>$relTime</RelTime><TrackDuration>$trackDuration</TrackDuration>"
            "GetVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
            "GetMute" -> "<CurrentMute>$mute</CurrentMute>"
            else -> ""
        }
        val envelope = """<?xml version="1.0"?><s:Envelope><s:Body><u:${action}Response>$inner</u:${action}Response></s:Body></s:Envelope>"""
        return newFixedLengthResponse(Response.Status.OK, "text/xml", envelope)
    }

    private fun readBody(session: IHTTPSession): String {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun callsFor(action: String): List<SoapCall> = soapCalls.filter { it.action == action }

    companion object {
        private val ACTION_REGEX = "<u:([A-Za-z]+)".toRegex()
    }
}
