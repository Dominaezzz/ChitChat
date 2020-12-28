package me.dominaezzz.chitchat.sdk.crypto

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.sdk.util.JsonVisitor

object JsonSorter : JsonVisitor() {
	private fun <T : Comparable<T>> Iterable<T>.isSorted(): Boolean {
		return zipWithNext { left, right -> left <= right }.all { it }
	}

	override fun visitObject(json: JsonObject): JsonElement {
		return if (json.keys.isSorted()) {
			super.visitObject(json)
		} else {
			JsonObject(json.keys.sorted().associateWith { visit(json.getValue(it)) })
		}
	}
}
