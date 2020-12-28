package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.olm.Account
import io.github.matrixkt.olm.Utility
import io.github.matrixkt.utils.MatrixJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

fun <T> getCanonJson(serializer: SerializationStrategy<T>, value: T): String {
	val json = MatrixJson.encodeToJsonElement(serializer, value)
	return getCanonJson(json)
}

fun getCanonJson(json: JsonElement): String {
	return Json.encodeToString(JsonElement.serializer(), JsonSorter.visit(json))
}

private fun getSignableCanonJson(json: JsonElement): String {
	val jsonToSign = if (json is JsonObject) {
		JsonObject(json.filterKeys { !(it == "signatures" || it == "unsigned") })
	} else {
		json
	}
	return getCanonJson(jsonToSign)
}

private fun JsonObjectBuilder.editJsonObject(key: String, builderAction: JsonObjectBuilder.() -> Unit) {
	val prev = put(key, "TEMP_FOR_EDITING")
	putJsonObject(key) {
		if (prev != null) {
			require(prev is JsonObject)
			for ((k, v) in prev) put(k, v)
		}
		builderAction()
	}
}

fun Account.signObject(
	json: JsonObject,
	userId: String,
	deviceId: String
): JsonObject {
	return buildJsonObject {
		for ((key, elem) in json) put(key, elem)

		editJsonObject("signatures") {
			editJsonObject(userId) {
				val toSign = getSignableCanonJson(json)
				val signature = sign(toSign)

				put("ed25519:$deviceId", signature)
			}
		}
	}
}

fun <T> Account.signObject(
	serializer: KSerializer<T>,
	value: T,
	userId: String,
	deviceId: String
): T {
	val json = MatrixJson.encodeToJsonElement(serializer, value)
	require(json is JsonObject) { "Can only sign json objects" }
	val newJson = signObject(json, userId, deviceId)
	return MatrixJson.decodeFromJsonElement(serializer, newJson)
}

inline fun <T> usingUtility(block: (Utility) -> T): T {
	val utility = Utility()
	return try {
		block(utility)
	} finally {
		utility.clear()
	}
}

private val SignaturesSerializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))

fun Utility.verifyEd25519Signature(
	userId: String,
	deviceId: String,
	signingKey: String,
	json: JsonObject
) {
	val canonicalJson = getSignableCanonJson(json)

	val signaturesJson = requireNotNull(json["signatures"]) { "No signatures found" }

	val signatures = Json.decodeFromJsonElement(SignaturesSerializer, signaturesJson)
	val userSignatures = requireNotNull(signatures[userId]) { "No signatures found" }
	val signature = requireNotNull(userSignatures["ed25519:$deviceId"]) { "No supported signature found" }

	verifyEd25519Signature(signingKey, canonicalJson, signature)
}

fun <T> Utility.verifyEd25519Signature(
	userId: String,
	deviceId: String,
	signingKey: String,
	serializer: SerializationStrategy<T>,
	value: T
) {
	val json = Json.encodeToJsonElement(serializer, value)
	require(json is JsonObject) { "Can only verify json objects" }
	verifyEd25519Signature(userId, deviceId, signingKey, json)
}
