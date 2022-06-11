package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.events.EncryptedFile
import io.github.matrixkt.events.JWK
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.random.asKotlinRandom

object Attachments {
	private const val BUFFER_SIZE = 32 * 1024
	private const val CIPHER_ALGORITHM = "AES/CTR/NoPadding"
	private const val SECRET_KEY_SPEC_ALGORITHM = "AES"
	private const val MESSAGE_DIGEST_ALGORITHM = "SHA-256"

	// TODO: Need to break these down into smaller low-level helpers.
	//  these are too high-level and specific to be SDK worthy.

	fun decrypt(inputStream: InputStream, outputStream: OutputStream, fileInfo: EncryptedFile) {
		val cipher = Cipher.getInstance(CIPHER_ALGORITHM)

		val key = Base64.getUrlDecoder().decode(fileInfo.key.key)
		val iv = Base64.getDecoder().decode(fileInfo.initialisationVector)
		cipher.init(
			Cipher.DECRYPT_MODE,
			SecretKeySpec(key, SECRET_KEY_SPEC_ALGORITHM),
			IvParameterSpec(iv)
		)

		val digest = MessageDigest.getInstance(MESSAGE_DIGEST_ALGORITHM)

		inputStream.digest(digest).cipher(cipher).copyTo(outputStream, BUFFER_SIZE)

		val expectedHash = Base64.getEncoder().withoutPadding().encodeToString(digest.digest())
		check(fileInfo.hashes.getValue("sha256") == expectedHash) // Dangerous, need to fix.
	}

	fun encrypt(inputStream: InputStream, outputStream: OutputStream, random: Random = SecureRandom().asKotlinRandom()): EncryptedFile {
		val cipher = Cipher.getInstance(CIPHER_ALGORITHM)

		val key = random.nextBytes(ByteArray(16), toIndex = 8)
		val iv = random.nextBytes(32)
		cipher.init(
			Cipher.ENCRYPT_MODE,
			SecretKeySpec(key, SECRET_KEY_SPEC_ALGORITHM),
			IvParameterSpec(iv)
		)

		val digest = MessageDigest.getInstance(MESSAGE_DIGEST_ALGORITHM)

		inputStream.cipher(cipher).digest(digest).copyTo(outputStream, BUFFER_SIZE)

		val hash = Base64.getEncoder().withoutPadding().encodeToString(digest.digest())

		return EncryptedFile(
			url = "TODO",
			key = JWK(
				keyType = "oct",
				keyOps = listOf("encrypt", "decrypt"),
				algorithm = "A256CTR",
				key = Base64.getUrlEncoder().encodeToString(key),
				extractable = true
			),
			initialisationVector = Base64.getEncoder().encodeToString(iv),
			hashes = mapOf("sha256" to hash),
			version = "v2"
		)
	}

	private fun InputStream.digest(digest: MessageDigest): DigestInputStream {
		return DigestInputStream(this, digest)
	}

	private fun OutputStream.digest(digest: MessageDigest): DigestOutputStream {
		return DigestOutputStream(this, digest)
	}

	private fun InputStream.cipher(cipher: Cipher): CipherInputStream {
		return CipherInputStream(this, cipher)
	}

	private fun OutputStream.cipher(cipher: Cipher): CipherOutputStream {
		return CipherOutputStream(this, cipher)
	}
}
