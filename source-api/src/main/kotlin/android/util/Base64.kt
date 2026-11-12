package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray = decode(str.toByteArray(Charsets.US_ASCII), flags)

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray = decode(input, 0, input.size, flags)

    @JvmStatic
    fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
        val raw = String(input, offset, len, Charsets.US_ASCII).filterNot { it.isWhitespace() }
        val normalized = raw.replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            java.util.Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad base-64", e)
        }
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String = String(encode(input, flags), Charsets.US_ASCII)

    @JvmStatic
    fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String =
        String(encode(input, offset, len, flags), Charsets.US_ASCII)

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray = encode(input, 0, input.size, flags)

    @JvmStatic
    fun encode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
        val slice = input.copyOfRange(offset, offset + len)
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        var out = encoder.encodeToString(slice)
        if (flags and NO_WRAP == 0) {
            val eol = if (flags and CRLF != 0) "\r\n" else "\n"
            out = out.chunked(76).joinToString(eol, postfix = eol)
        }
        return out.toByteArray(Charsets.US_ASCII)
    }
}
