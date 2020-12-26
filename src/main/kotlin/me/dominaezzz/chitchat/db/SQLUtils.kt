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

var Connection.version: Int
    get() = usingStatement { stmt -> stmt.executeQuery("PRAGMA user_version;").use { check(it.next()); it.getInt(1) } }
    set(value) { usingStatement { stmt -> stmt.executeUpdate("PRAGMA user_version = $value;") } }

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
        getJsonElement("unsigned")?.jsonObject,
        getString("roomId"),
        getString("stateKey"),
        getJsonElement("prevContent")?.jsonObject
    )
}
