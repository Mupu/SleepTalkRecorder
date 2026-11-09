package me.mupu.sleeptalkrecorder.models

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** Where the bytes of a model file come from. */
fun interface ByteSource {

    /**
     * Opens [url] to read it from byte [offset] on. A source that cannot resume sends the whole
     * file instead, which its [Body.offset] of 0 tells.
     */
    @Throws(IOException::class)
    fun open(url: String, offset: Long): Body

    /** The bytes of a file from [offset] on. */
    class Body(val offset: Long, val stream: InputStream) : Closeable by stream
}

/** Reads files over HTTPS, resuming with a `Range` request; redirects (to a CDN) are followed. */
object HttpByteSource : ByteSource {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000

    override fun open(url: String, offset: Long): ByteSource.Body {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")

        try {
            return when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> ByteSource.Body(0, connection.inputStream)
                HttpURLConnection.HTTP_PARTIAL -> {
                    // "bytes 1000-6355276/6355277": the body must start where the file stopped.
                    val start = connection.getHeaderField("Content-Range")
                        ?.removePrefix("bytes ")
                        ?.substringBefore('-')
                        ?.toLongOrNull()
                    if (start != offset) throw IOException("Resumed at byte $start instead of $offset: $url")
                    ByteSource.Body(offset, connection.inputStream)
                }
                else -> throw IOException("HTTP $status for $url")
            }
        } catch (failure: IOException) {
            connection.disconnect()
            throw failure
        }
    }
}
