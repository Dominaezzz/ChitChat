package me.dominaezzz.chitchat.db

import io.github.matrixkt.models.events.MatrixEvent
import io.github.matrixkt.models.events.UnsignedData
import io.github.matrixkt.utils.MatrixJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import me.dominaezzz.chitchat.projectDir
import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

fun getConnection(dbFile: Path): Connection {
    val config = SQLiteConfig()
    config.enforceForeignKeys(true)
    config.setJournalMode(SQLiteConfig.JournalMode.WAL)
    // config.busyTimeout = 5000

    return DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}", config.toProperties())
}

@OptIn(ExperimentalContracts::class)
inline fun <T> usingConnection(block: (Connection) -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return getConnection(projectDir.resolve("app.db")).use(block)
}

@OptIn(ExperimentalContracts::class)
inline fun <T> Connection.usingStatement(block: (Statement) -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return createStatement().use(block)
}

@OptIn(ExperimentalContracts::class)
inline fun <T> usingStatement(block: (Statement) -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return usingConnection { it.usingStatement(block) }
}

@OptIn(ExperimentalContracts::class)
inline fun Connection.savepoint(block: () -> Boolean) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val savepoint = setSavepoint()

    var isSavepointSuccessful = false
    var exception: Throwable? = null
    try {
        isSavepointSuccessful = block()
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
            if (isSavepointSuccessful) {
                releaseSavepoint(savepoint)
            } else {
                rollback(savepoint)
            }
        }
    }
}

var Connection.version: Int
    get() = usingStatement { stmt -> stmt.executeQuery("PRAGMA user_version;").use { check(it.next()); it.getInt(1) } }
    set(value) { usingStatement { stmt -> stmt.executeUpdate("PRAGMA user_version = $value;") } }

fun Connection.getValue(key: String): String? {
    return prepareStatement("SELECT value FROM key_value_store WHERE key = ?;").use { stmt ->
        stmt.setString(1, key)
        stmt.executeQuery().use { rs ->
            if (rs.next()) {
                rs.getString(1)
            } else {
                null
            }
        }
    }
}

fun Connection.setValue(key: String, value: String?) {
    prepareStatement(
        """
            INSERT INTO key_value_store(key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value;
        """
    ).use {
        it.setString(1, key)
        it.setString(2, value)
        it.executeUpdate()
    }
}

fun ResultSet.getJsonElement(columnLabel: String): JsonElement? {
    val content = getString(columnLabel)
    return if (content != null) {
        MatrixJson.parseToJsonElement(content)
    } else {
        null
    }
}

fun ResultSet.getJsonElement(columnIndex: Int): JsonElement? {
    val content = getString(columnIndex)
    return if (content != null) {
        MatrixJson.parseToJsonElement(content)
    } else {
        null
    }
}

fun <T> ResultSet.getSerializable(columnLabel: String, deserializer: DeserializationStrategy<T>): T? {
    val content = getString(columnLabel)
    return if (content != null) {
        MatrixJson.decodeFromString(deserializer, content)
    } else {
        null
    }
}

fun <T> ResultSet.getSerializable(columnIndex: Int, deserializer: DeserializationStrategy<T>): T? {
    val content = getString(columnIndex)
    return if (content != null) {
        MatrixJson.decodeFromString(deserializer, content)
    } else {
        null
    }
}

fun <T> PreparedStatement.setSerializable(parameterIndex: Int, serializer: SerializationStrategy<T>, value: T?) {
    if (value != null) {
        val content = MatrixJson.encodeToString(serializer, value)
        setString(parameterIndex, content)
    } else {
        setString(parameterIndex, null)
    }
}

fun ResultSet.getEvent(): MatrixEvent {
    return MatrixEvent(
        getString("type"),
        getJsonElement("content")!!.jsonObject,
        getString("eventId"),
        getString("sender"),
        getLong("timestamp"),
        getSerializable("unsigned", UnsignedData.serializer()),
        getString("roomId"),
        getString("stateKey"),
        getJsonElement("prevContent")?.jsonObject
    )
}
