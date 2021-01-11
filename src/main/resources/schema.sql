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


CREATE TABLE key_value_store
(
    key   TEXT PRIMARY KEY NOT NULL,
    value TEXT
);
