CREATE TABLE tracked_users
(
    userId     TEXT    NOT NULL PRIMARY KEY,
    isOutdated BOOLEAN NOT NULL DEFAULT FALSE,
    sync_token TEXT
);

CREATE TABLE device_list
(
    userId     TEXT NOT NULL,
    deviceId   TEXT NOT NULL,
    algorithms TEXT NOT NULL CHECK (JSON_VALID(algorithms)),
    keys       TEXT NOT NULL CHECK (JSON_VALID(keys)),
    signatures TEXT NOT NULL CHECK (JSON_VALID(signatures)),
    unsigned   TEXT NOT NULL CHECK (JSON_VALID(unsigned)),

    json       TEXT GENERATED ALWAYS AS (
        JSON_OBJECT(
            'user_id', userId,
            'device_id', deviceId,
            'algorithms', JSON(algorithms),
            'keys', JSON(keys),
            'signatures', JSON(signatures),
            'unsigned', JSON(unsigned)
        )
    ),

    PRIMARY KEY (userId, deviceId),
    FOREIGN KEY (userId) REFERENCES tracked_users (userId)
);
