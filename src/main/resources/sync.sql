CREATE TABLE key_value_store
(
    key   TEXT PRIMARY KEY NOT NULL,
    value TEXT
);

CREATE TABLE room_metadata
(
    roomId                TEXT PRIMARY KEY NOT NULL,
    summary               TEXT,
    loadedMembershipTypes TEXT NOT NULL CHECK (JSON_VALID(loadedMembershipTypes))
);

CREATE TABLE room_timelines
(
    roomId     TEXT    NOT NULL,
    timelineId INTEGER NOT NULL,
    token      TEXT,

    PRIMARY KEY (roomId, timelineId),
    FOREIGN KEY (roomId) REFERENCES room_metadata(roomId)
        ON DELETE CASCADE
);

CREATE TABLE room_events
(
    roomId      TEXT    NOT NULL,
    eventId     TEXT    NOT NULL,
    type        TEXT    NOT NULL,
    content     TEXT    NOT NULL CHECK (JSON_VALID(content)),
    sender      TEXT    NOT NULL,
    timestamp   INTEGER NOT NULL,
    unsigned    TEXT    CHECK (unsigned IS NULL OR JSON_VALID(unsigned)),
    stateKey    TEXT,
    prevContent TEXT    CHECK (prevContent IS NULL OR JSON_VALID(prevContent)),

    json        TEXT GENERATED ALWAYS AS (
        JSON_OBJECT(
            'room_id', roomId,
            'event_id', eventId,
            'type', type,
            'content', JSON(content),
            'sender', sender,
            'origin_server_ts', timestamp,
            'unsigned', JSON(unsigned),
            'state_key', stateKey,
            'prev_content', JSON(prevContent)
        )
    ),

    timelineId    INTEGER NOT NULL,
    timelineOrder INTEGER CHECK (timelineOrder IS NOT NULL OR stateKey IS NOT NULL),

    isLatestState BOOLEAN NOT NULL DEFAULT FALSE CHECK (NOT isLatestState OR stateKey NOT NULL),

    PRIMARY KEY (roomId, eventId),
    FOREIGN KEY (roomId, timelineId) REFERENCES room_timelines(roomId, timelineId)
        ON UPDATE CASCADE,
    FOREIGN KEY (roomId) REFERENCES room_metadata(roomId)
        ON DELETE CASCADE
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

CREATE TABLE room_invitations
(
    roomId   TEXT NOT NULL,
    type     TEXT NOT NULL,
    stateKey TEXT NOT NULL,
    sender   TEXT NOT NULL,
    content  TEXT NOT NULL CHECK (JSON_VALID(content)),

    PRIMARY KEY (roomId, type, stateKey)
);

CREATE TABLE account_data
(
    type    TEXT NOT NULL,
    roomId  TEXT,
    content TEXT NOT NULL CHECK (JSON_VALID(content)),

    FOREIGN KEY (roomId) REFERENCES room_metadata(roomId)
        ON DELETE CASCADE
);
CREATE UNIQUE INDEX global_account_data ON account_data(type) WHERE roomId IS NULL;
CREATE UNIQUE INDEX room_account_data ON account_data(roomId, type) WHERE roomId IS NOT NULL;

CREATE TABLE room_receipts
(
    roomId  TEXT NOT NULL,
    userId  TEXT NOT NULL,
    type    TEXT NOT NULL,
    eventId TEXT NOT NULL,
    content TEXT NOT NULL CHECK (JSON_VALID(content)),

    PRIMARY KEY (roomId, userId, type),
    FOREIGN KEY (roomId) REFERENCES room_metadata(roomId)
        ON DELETE CASCADE
);



CREATE TABLE device_events
(
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    type    TEXT NOT NULL,
    content TEXT NOT NULL CHECK (JSON_VALID(content)),
    sender  TEXT NOT NULL
);
