CREATE TABLE room_events
(
    roomId        TEXT    NOT NULL,
    eventId       TEXT    NOT NULL,
    type          TEXT    NOT NULL,
    content       TEXT    NOT NULL,
    sender        TEXT    NOT NULL,
    timestamp     INTEGER NOT NULL,
    unsigned      TEXT,
    stateKey      TEXT,
    prevContent   TEXT,

    timelineId    INTEGER NOT NULL DEFAULT 0,
    timelineOrder INTEGER,

    isLatestState BOOLEAN NOT NULL DEFAULT FALSE CHECK (NOT isLatestState OR stateKey NOT NULL),

    PRIMARY KEY (roomId, eventId)
);

CREATE INDEX room_timeline ON room_events (roomId, timelineId, timelineOrder DESC, type, stateKey);
CREATE UNIQUE INDEX latest_room_state ON room_events (roomId, type, stateKey) WHERE isLatestState;
CREATE UNIQUE INDEX compressed_state ON room_events (roomId, timelineId, type, stateKey) WHERE timelineOrder IS NULL;

CREATE TRIGGER latest_room_state
    AFTER INSERT
    ON room_events
    WHEN NOT NEW.isLatestState AND NEW.stateKey NOT NULL
BEGIN
    UPDATE room_events
    SET isLatestState = FALSE
    WHERE isLatestState
      AND roomId = NEW.roomId
      AND type = NEW.type
      AND stateKey = NEW.stateKey
      AND (timelineId, -COALESCE(timelineOrder, 0)) > (NEW.timelineId, -COALESCE(NEW.timelineOrder, 0));
    UPDATE room_events
    SET isLatestState = TRUE
    WHERE roomId = NEW.roomId
      AND eventId = NEW.eventId
      AND NOT EXISTS(
            SELECT 1
            FROM room_events
            WHERE isLatestState
              AND roomId = NEW.roomId
              AND type = NEW.type
              AND stateKey = NEW.stateKey
        );
END;

CREATE TRIGGER maintain_event_prev_content
    AFTER INSERT
    ON room_events
    WHEN NEW.stateKey IS NOT NULL
BEGIN
    UPDATE room_events
    SET prevContent = (
        SELECT content
        FROM room_events
        WHERE roomId = NEW.roomId
          AND type = NEW.type
          AND stateKey = NEW.stateKey
          AND timelineId = NEW.timelineId
          AND COALESCE(timelineOrder, 0) < COALESCE(NEW.timelineOrder, 0)
        ORDER BY timelineOrder DESC
        LIMIT 1
    )
    WHERE roomId = NEW.roomId AND eventId = NEW.eventId AND NEW.timelineOrder IS NOT NULL AND prevContent IS NULL;

    UPDATE room_events
    SET prevContent = NEW.content
    WHERE roomId = NEW.roomId AND prevContent IS NULL AND eventId = (
        SELECT eventId
        FROM room_events
        WHERE roomId = NEW.roomId
          AND type = NEW.type
          AND stateKey = NEW.stateKey
          AND timelineId = NEW.timelineId
          AND COALESCE(timelineOrder, 0) > COALESCE(NEW.timelineOrder, 0)
        ORDER BY timelineOrder ASC
        LIMIT 1
    );
END;

CREATE TABLE room_invitations
(
    roomId   TEXT NOT NULL,
    type     TEXT NOT NULL,
    stateKey TEXT NOT NULL,
    sender   TEXT NOT NULL,
    content  TEXT NOT NULL,

    PRIMARY KEY (roomId, type, stateKey)
);

CREATE TABLE room_pagination_tokens
(
    roomId  TEXT NOT NULL,
    eventId TEXT NOT NULL,
    token   TEXT NOT NULL,

    PRIMARY KEY (roomId, eventId),
    FOREIGN KEY (roomId, eventId) REFERENCES room_events (roomId, eventId)
        ON DELETE CASCADE
);

CREATE TABLE room_receipts
(
    roomId  TEXT NOT NULL,
    userId  TEXT NOT NULL,
    type    TEXT NOT NULL,
    eventId TEXT NOT NULL,
    content TEXT NOT NULL,

    PRIMARY KEY (roomId, userId, type),
    FOREIGN KEY (roomId) REFERENCES room_metadata (roomId)
        ON DELETE CASCADE
);

CREATE TABLE olm_sessions
(
    sessionId         TEXT    PRIMARY KEY NOT NULL,
    identityKey       TEXT    NOT NULL,
    pickle            TEXT    NOT NULL,
    isOutbound        BOOLEAN NOT NULL,
    lastSuccessfulUse INTEGER NOT NULL DEFAULT (STRFTIME('%s', 'now'))
);

CREATE TABLE megolm_sessions
(
    roomId           TEXT NOT NULL,
    senderKey        TEXT NOT NULL,
    sessionId        TEXT NOT NULL,
    pickle           TEXT NOT NULL,
    ed25519Key       TEXT NOT NULL,
    forwardingChain  TEXT NOT NULL DEFAULT (JSON_ARRAY()),
    PRIMARY KEY (roomId, senderKey, sessionId)
);

CREATE TABLE device_events
(
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    type    TEXT NOT NULL,
    content TEXT NOT NULL,
    sender  TEXT NOT NULL
);

CREATE TABLE room_metadata
(
    roomId                TEXT PRIMARY KEY NOT NULL,
    summary               TEXT,
    loadedMembershipTypes TEXT NOT NULL CHECK (JSON_VALID(loadedMembershipTypes))
);

CREATE TABLE account_data
(
    type    TEXT NOT NULL,
    roomId  TEXT,
    content TEXT NOT NULL -- CHECK (JSON_VALID(content))
);
CREATE UNIQUE INDEX global_account_data_index ON account_data(type) WHERE roomId IS NULL;
CREATE UNIQUE INDEX room_account_data_index ON account_data(roomId, type) WHERE roomId IS NOT NULL;

CREATE TABLE tracked_users
(
    userId     TEXT    NOT NULL PRIMARY KEY,
    isOutdated BOOLEAN NOT NULL DEFAULT FALSE,
    sync_token TEXT
);

CREATE TABLE device_list
(
    userId            TEXT    NOT NULL,
    deviceId          TEXT    NOT NULL,
    algorithms        TEXT    NOT NULL,
    keys              TEXT    NOT NULL,
    signatures        TEXT    NOT NULL,
    unsigned          TEXT    NOT NULL,
    json              TEXT AS (JSON_OBJECT(
        'user_id', userId,
        'device_id', deviceId,
        'algorithms', JSON(algorithms),
        'keys', JSON(keys),
        'signatures', JSON(signatures),
        'unsigned', JSON(unsigned)
    )),
    PRIMARY KEY (userId, deviceId),
    FOREIGN KEY (userId) REFERENCES tracked_users (userId)
);

CREATE TABLE key_value_store
(
    key   TEXT PRIMARY KEY NOT NULL,
    value TEXT
);
