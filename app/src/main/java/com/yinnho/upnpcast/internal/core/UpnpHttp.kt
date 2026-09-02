package com.yinnho.upnpcast.internal.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared HTTP execution for UPnP traffic (device description retrieval and
 * SOAP control requests), so timeouts, headers and connection handling stay
 * consistent across the library
 */
internal object UpnpHttp {

    private const val USER_AGENT = "UPnPCast/1.0"

    /**
     * GET a resource body; returns null on failure. Retries transient
     * failures (non-200 or IO error) up to [maxRetries] times with a linear
     * backoff of [retryDelayMs] * attempt.
     */
    suspend fun get(
        url: String,
        connectTimeoutMs: Int = 5000,
        readTimeoutMs: Int = 10000,
        maxRetries: Int = 1,
        retryDelayMs: Long = 1000L
    ): String? = withContext(Dispatchers.IO) {
        repeat(maxRetries) { attempt ->
            val attemptNumber = attempt + 1
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                        return@withContext reader.readText()
                    }
                }
            } catch (e: Exception) {
                // Fall through to retry
            } finally {
                connection?.disconnect()
            }
            if (attemptNumber < maxRetries) {
                delay(retryDelayMs * attemptNumber)
            }
        }
        null
    }

    /**
     * POST a SOAP envelope with the given action header; returns the
     * response body on HTTP 200, null otherwise.
     */
    suspend fun postSoap(
        url: String,
        soapAction: String,
        body: String,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 5000
    ): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"$soapAction\"")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs

            connection.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                    writer.write(soapEnvelope(body))
                    writer.flush()
                }
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                    reader.readText()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun soapEnvelope(body: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
                $body
            </s:Body>
        </s:Envelope>
    """.trimIndent()
}
