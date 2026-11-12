package inkoma.extension

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies APK Signature Scheme v2/v3 signatures, as Android's package manager does before an extension can be
 * installed, and returns the signer certificates. Store extensions are v2-only (no META-INF signature files), so this is
 * required to obtain the fingerprint Mihon's trust model compares against the store's signing key.
 *
 * Spec: https://source.android.com/docs/security/features/apksigning/v2
 */
object ApkSignatureVerifier {
    private const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"
    private const val V2_BLOCK_ID = 0x7109871a
    private const val V3_BLOCK_ID = 0xf05368c0.toInt()
    private const val CHUNK_SIZE = 1024 * 1024

    class VerificationException(message: String) : Exception(message)

    /** Returns the certificates of the first signer, or null if the APK has no v2/v3 signature. Throws if invalid. */
    fun verify(apk: File): List<X509Certificate>? {
        RandomAccessFile(apk, "r").use { raf ->
            val (eocdOffset, eocd) = findEocd(raf) ?: throw VerificationException("Not a ZIP file")
            val cdOffset = eocd.getInt(16).toLong() and 0xffffffffL
            val cdSize = eocd.getInt(12).toLong() and 0xffffffffL
            if (cdOffset + cdSize != eocdOffset) throw VerificationException("ZIP central directory is not adjacent to EOCD")
            val sigBlock = readSigningBlock(raf, cdOffset) ?: return null
            val pairs = parsePairs(sigBlock.block)
            val schemeBlock = pairs[V3_BLOCK_ID] ?: pairs[V2_BLOCK_ID] ?: return null
            val signers = lengthPrefixedSequence(schemeBlock.order(ByteOrder.LITTLE_ENDIAN))
            if (signers.isEmpty()) throw VerificationException("No signers")
            var firstCerts: List<X509Certificate>? = null
            val contentDigests = HashMap<Int, ByteArray>()
            for (signer in signers) {
                val certs = verifySigner(signer, contentDigests)
                if (firstCerts == null) firstCerts = certs
            }
            // Verify every content digest that a signer committed to against the actual file contents.
            for ((algorithm, expected) in contentDigests) {
                val actual = computeContentDigest(raf, algorithm, sigBlock.offset, cdOffset, eocdOffset, eocd)
                if (!MessageDigest.isEqual(expected, actual)) throw VerificationException("APK contents digest mismatch")
            }
            return firstCerts
        }
    }

    private fun findEocd(raf: RandomAccessFile): Pair<Long, ByteBuffer>? {
        val fileSize = raf.length()
        val maxComment = minOf(fileSize - 22, 0xffffL)
        if (fileSize < 22) return null
        val buf = ByteArray((22 + maxComment).toInt())
        raf.seek(fileSize - buf.size)
        raf.readFully(buf)
        for (i in buf.size - 22 downTo 0) {
            if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() && buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()) {
                val commentLen = (buf[i + 20].toInt() and 0xff) or ((buf[i + 21].toInt() and 0xff) shl 8)
                if (i + 22 + commentLen == buf.size) {
                    return (fileSize - buf.size + i) to ByteBuffer.wrap(buf, i, buf.size - i).slice().order(ByteOrder.LITTLE_ENDIAN)
                }
            }
        }
        return null
    }

    private class SigningBlock(val offset: Long, val block: ByteBuffer)

    private fun readSigningBlock(raf: RandomAccessFile, cdOffset: Long): SigningBlock? {
        if (cdOffset < 32) return null
        val footer = ByteArray(24)
        raf.seek(cdOffset - 24)
        raf.readFully(footer)
        if (String(footer, 8, 16, Charsets.US_ASCII) != APK_SIG_BLOCK_MAGIC) return null
        val sizeInFooter = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val total = sizeInFooter + 8
        if (sizeInFooter < 24 || total > cdOffset || total > Int.MAX_VALUE) throw VerificationException("Bad signing block size")
        val start = cdOffset - total
        val bytes = ByteArray(total.toInt())
        raf.seek(start)
        raf.readFully(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getLong(0) != sizeInFooter) throw VerificationException("Signing block sizes differ")
        // Pairs live between the leading size field and the 24-byte footer.
        val pairs = ByteBuffer.wrap(bytes, 8, bytes.size - 32).slice().order(ByteOrder.LITTLE_ENDIAN)
        return SigningBlock(start, pairs)
    }

    private fun parsePairs(block: ByteBuffer): Map<Int, ByteBuffer> {
        val out = HashMap<Int, ByteBuffer>()
        while (block.remaining() >= 8) {
            val len = block.long
            if (len < 4 || len > block.remaining()) throw VerificationException("Bad signing block pair")
            val id = block.int
            val value = sliceOf(block, (len - 4).toInt())
            out[id] = value
        }
        return out
    }

    private fun sliceOf(buf: ByteBuffer, size: Int): ByteBuffer {
        if (size < 0 || size > buf.remaining()) throw VerificationException("Truncated signature data")
        val s = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
        s.limit(size)
        buf.position(buf.position() + size)
        return s
    }

    private fun lengthPrefixed(buf: ByteBuffer): ByteBuffer = sliceOf(buf, buf.int)

    private fun lengthPrefixedSequence(buf: ByteBuffer): List<ByteBuffer> {
        val seq = lengthPrefixed(buf)
        val out = ArrayList<ByteBuffer>()
        while (seq.hasRemaining()) out.add(lengthPrefixed(seq))
        return out
    }

    private fun ByteBuffer.bytes(): ByteArray {
        val dup = duplicate()
        return ByteArray(dup.remaining()).also { dup.get(it) }
    }

    private data class Algo(val jcaSignature: String, val keyAlgorithm: String, val digest: String, val pss: PSSParameterSpec? = null)

    private fun algorithm(id: Int): Algo? = when (id) {
        0x0101 -> Algo("RSASSA-PSS", "RSA", "SHA-256", PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        0x0102 -> Algo("RSASSA-PSS", "RSA", "SHA-512", PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1))
        0x0103 -> Algo("SHA256withRSA", "RSA", "SHA-256")
        0x0104 -> Algo("SHA512withRSA", "RSA", "SHA-512")
        0x0201 -> Algo("SHA256withECDSA", "EC", "SHA-256")
        0x0202 -> Algo("SHA512withECDSA", "EC", "SHA-512")
        0x0301 -> Algo("SHA256withDSA", "DSA", "SHA-256")
        else -> null
    }

    /** Verifies one signer and records its content digests; returns its certificates. */
    private fun verifySigner(signer: ByteBuffer, contentDigests: MutableMap<Int, ByteArray>): List<X509Certificate> {
        val signedData = lengthPrefixed(signer)
        val signedDataBytes = signedData.bytes()
        val signatures = lengthPrefixedSequence(signer)
        val publicKeyBytes = lengthPrefixed(signer).bytes()

        val keyAlgorithms = listOf("RSA", "EC", "DSA")
        var verifiedAlgorithm: Int? = null
        var publicKey: PublicKey? = null
        for (sig in signatures) {
            val id = sig.int
            val algo = algorithm(id) ?: continue
            val sigBytes = lengthPrefixed(sig).bytes()
            val key = publicKey ?: runCatching {
                KeyFactory.getInstance(algo.keyAlgorithm).generatePublic(X509EncodedKeySpec(publicKeyBytes))
            }.getOrNull() ?: continue
            publicKey = key
            val verifier = Signature.getInstance(algo.jcaSignature)
            verifier.initVerify(key)
            algo.pss?.let { verifier.setParameter(it) }
            verifier.update(signedDataBytes)
            if (!verifier.verify(sigBytes)) throw VerificationException("Signature over signed data does not verify")
            verifiedAlgorithm = id
            break
        }
        if (verifiedAlgorithm == null || publicKey == null) throw VerificationException("No supported signature algorithm (keys: $keyAlgorithms)")

        val digests = lengthPrefixedSequence(signedData)
        val certificates = lengthPrefixedSequence(signedData)
        val factory = CertificateFactory.getInstance("X.509")
        val certs = certificates.map { factory.generateCertificate(ByteArrayInputStream(it.bytes())) as X509Certificate }
        if (certs.isEmpty()) throw VerificationException("Signer has no certificates")
        if (!certs.first().publicKey.encoded.contentEquals(publicKeyBytes)) throw VerificationException("Certificate does not match the signing key")

        for (d in digests) {
            val id = d.int
            val value = lengthPrefixed(d).bytes()
            if (algorithm(id) != null) {
                val previous = contentDigests[id]
                if (previous != null && !previous.contentEquals(value)) throw VerificationException("Signers disagree on contents digest")
                contentDigests[id] = value
            }
        }
        if (contentDigests.isEmpty()) throw VerificationException("No supported contents digest")
        return certs
    }

    /** Chunked digest over [0, sigBlock), central directory, and EOCD with the CD offset pointing at the signing block. */
    private fun computeContentDigest(
        raf: RandomAccessFile,
        algorithmId: Int,
        sigBlockOffset: Long,
        cdOffset: Long,
        eocdOffset: Long,
        eocd: ByteBuffer,
    ): ByteArray {
        val digestName = algorithm(algorithmId)!!.digest
        val eocdCopy = eocd.bytes()
        ByteBuffer.wrap(eocdCopy).order(ByteOrder.LITTLE_ENDIAN).putInt(16, sigBlockOffset.toInt())
        val sections = listOf(
            Triple(0L, sigBlockOffset, null as ByteArray?),
            Triple(cdOffset, eocdOffset - cdOffset, null),
            Triple(0L, eocdCopy.size.toLong(), eocdCopy),
        )
        val chunkDigests = ArrayList<ByteArray>()
        val chunk = ByteArray(CHUNK_SIZE)
        val prefix = ByteArray(5)
        for ((start, length, inMemory) in sections) {
            var pos = 0L
            while (pos < length) {
                val n = minOf(CHUNK_SIZE.toLong(), length - pos).toInt()
                if (inMemory != null) {
                    System.arraycopy(inMemory, (start + pos).toInt(), chunk, 0, n)
                } else {
                    raf.seek(start + pos)
                    raf.readFully(chunk, 0, n)
                }
                val md = MessageDigest.getInstance(digestName)
                prefix[0] = 0xa5.toByte()
                ByteBuffer.wrap(prefix, 1, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(n)
                md.update(prefix)
                md.update(chunk, 0, n)
                chunkDigests.add(md.digest())
                pos += n
            }
        }
        val top = MessageDigest.getInstance(digestName)
        prefix[0] = 0x5a
        ByteBuffer.wrap(prefix, 1, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkDigests.size)
        top.update(prefix)
        chunkDigests.forEach { top.update(it) }
        return top.digest()
    }
}
