package me.dominaezzz.chitchat.sdk.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

abstract class JsonVisitor {
	fun visit(json: JsonElement): JsonElement {
		return when (json) {
			is JsonObject -> visitObject(json)
			is JsonArray -> visitArray(json)
			is JsonPrimitive -> visitPrimitive(json)
		}
	}

	protected open fun visitObject(json: JsonObject): JsonElement {
		val newMap = mutableMapOf<String, JsonElement>()

		for ((key, value) in json) {
			val visitedValue = visit(value)
			if (value != visitedValue) {
				newMap[key] = visitedValue
			}
		}

		if (newMap.isNotEmpty()) {
			for ((key, value) in json) {
				if (key !in newMap) {
					newMap[key] = value
				}
			}
		}

		return if (newMap.isEmpty()) {
			json
		} else {
			JsonObject(newMap)
		}
	}

	protected open fun visitArray(json: JsonArray): JsonElement {
		var index = 0
		var newItem: JsonElement? = null

		while (index < json.size) {
			val item = json[index]
			val visitedItem = visit(item)
			index++
			if (item != visitedItem) {
				newItem = visitedItem
				break
			}
		}

		if (newItem == null) return json

		val newArray = ArrayList<JsonElement>(json.size)

		for (i in 0 until index) newArray.add(json[i])
		newArray.add(newItem); index++
		for (i in index until json.size) newArray.add(visit(json[i]))

		return JsonArray(newArray)
	}

	protected open fun visitPrimitive(json: JsonPrimitive): JsonElement {
		return json
	}
}
