package me.dominaezzz.chitchat.sdk.util

import io.github.matrixkt.utils.MatrixJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonNull
import java.sql.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
inline fun <T> Connection.usingStatement(block: (Statement) -> T): T {
	contract {
		callsInPlace(block, InvocationKind.EXACTLY_ONCE)
	}
	return createStatement().use(block)
}

@OptIn(ExperimentalContracts::class)
inline fun <T> Connection.transaction(block: () -> T): T {
	contract {
		callsInPlace(block, InvocationKind.EXACTLY_ONCE)
	}

	val prev = autoCommit
	autoCommit = false

	var exception: Throwable? = null
	try {
		return block()
	} catch (e: Throwable) {
		exception = e
		throw e
	} finally {
		if (exception != null) {
			try {
				rollback()
			} catch (rollbackException: Throwable) {
				@Suppress("UNNECESSARY_SAFE_CALL") // https://youtrack.jetbrains.com/issue/KT-28806
				exception?.addSuppressed(rollbackException)
			}
		} else {
			commit()
		}
		autoCommit = prev
	}
}

@OptIn(ExperimentalContracts::class)
inline fun <T> Connection.savepoint(block: () -> T): T {
	contract {
		callsInPlace(block, InvocationKind.EXACTLY_ONCE)
	}

	val savepoint = setSavepoint()

	var exception: Throwable? = null
	try {
		return block()
	} catch (e: Throwable) {
		exception = e
		throw e
	} finally {
		if (exception != null) {
			try {
				rollback(savepoint)
			} catch (rollbackException: Throwable) {
				@Suppress("UNNECESSARY_SAFE_CALL") // https://youtrack.jetbrains.com/issue/KT-28806
				exception?.addSuppressed(rollbackException)
			}
		} else {
			releaseSavepoint(savepoint)
		}
	}
}

@OptIn(ExperimentalContracts::class)
inline fun <T> Connection.withoutIndex(tableName: String, indexName: String, block: () -> T) {
	contract {
		callsInPlace(block, InvocationKind.EXACTLY_ONCE)
	}

	savepoint {
		val ddlStmt = prepareStatement("SELECT sql FROM sqlite_master WHERE type = ? AND tbl_name = ? AND name = ?").use { stmt ->
			stmt.setString(1, "index")
			stmt.setString(2, tableName)
			stmt.setString(3, indexName)
			stmt.executeQuery().use { rs ->
				if (!rs.next()) {
					throw NoSuchElementException("Index($indexName) on Table '${tableName}' does not exist!")
				}
				rs.getString(1)
			}
		}
		usingStatement { stmt ->
			stmt.execute("DROP INDEX $indexName;") // No way around this concatenation sadly.
			block()
			stmt.execute(ddlStmt)
		}
	}
}

fun <T> ResultSet.getSerializable(columnLabel: String, deserializer: DeserializationStrategy<T>): T {
	val content = getString(columnLabel) ?: JsonNull.content
	return MatrixJson.decodeFromString(deserializer, content)
}

fun <T> ResultSet.getSerializable(columnIndex: Int, deserializer: DeserializationStrategy<T>): T {
	val content = getString(columnIndex) ?: JsonNull.content
	return MatrixJson.decodeFromString(deserializer, content)
}

inline fun <reified T> ResultSet.getSerializable(columnIndex: Int): T {
	val content = getString(columnIndex) ?: JsonNull.content
	return MatrixJson.decodeFromString(content)
}

fun <T> PreparedStatement.setSerializable(parameterIndex: Int, serializer: SerializationStrategy<T>, value: T) {
	val content = MatrixJson.encodeToString(serializer, value)
	if (content != JsonNull.content) {
		setString(parameterIndex, content)
	} else {
		setString(parameterIndex, null)
	}
}
