package me.dominaezzz.chitchat.db

// language=sql
const val ROOM_INFO_SQL = """
WITH
    room_members AS (
        SELECT roomId, stateKey, content, ROW_NUMBER() OVER (PARTITION BY roomId ORDER BY stateKey) AS rowNum
        FROM room_events
        WHERE isLatestState AND type = 'm.room.member' AND JSON_EXTRACT(content, '${'$'}.membership') IN ('join', 'invite') AND stateKey != (SELECT value FROM key_value_store WHERE key = 'USER_ID')
    ),
    heroes AS (
        SELECT roomId, GROUP_CONCAT(IFNULL(JSON_EXTRACT(content, '${'$'}.displayname'), stateKey), ', ') AS members
        FROM room_members
        WHERE rowNum <= 5
        GROUP BY roomId
    ),
    member_count AS (
        SELECT roomId, COUNT(*) AS joined_member_count
        FROM room_events
        WHERE isLatestState AND type = 'm.room.member' AND JSON_EXTRACT(content, '${'$'}.membership') = 'join'
        GROUP BY roomId
    )
SELECT rooms.roomId,
       COALESCE(
               JSON_EXTRACT(name_event.content, '${'$'}.name'),
               JSON_EXTRACT(canon_alias_event.content, '${'$'}.alias'),
               JSON_EXTRACT(aliases_event.content, '${'$'}.aliases[0]'),
               members,
               'Empty Room'
           ) AS displayName,
       IFNULL(JSON_EXTRACT(avatar_event.content, '${'$'}.url'), JSON_EXTRACT(member_avatar.content, '${'$'}.avatar_url')) AS displayAvatar,
       joined_member_count AS memberCount,
       JSON_EXTRACT(topic_event.content, '${'$'}.topic') AS topic
FROM room_metadata AS rooms
LEFT JOIN room_events AS name_event ON name_event.isLatestState AND name_event.roomId = rooms.roomId AND name_event.type = 'm.room.name'
LEFT JOIN room_events AS canon_alias_event ON canon_alias_event.isLatestState AND canon_alias_event.roomId = rooms.roomId AND canon_alias_event.type = 'm.room.canonical_alias'
LEFT JOIN room_events AS aliases_event ON aliases_event.isLatestState AND aliases_event.roomId = rooms.roomId AND aliases_event.type = 'm.room.aliases'
LEFT JOIN heroes ON heroes.roomId == rooms.roomId
LEFT JOIN room_events AS avatar_event ON avatar_event.isLatestState AND avatar_event.roomId = rooms.roomId AND avatar_event.type = 'm.room.avatar'
LEFT JOIN room_events AS topic_event ON topic_event.isLatestState AND topic_event.roomId = rooms.roomId AND topic_event.type = 'm.room.topic'
LEFT JOIN room_members AS member_avatar ON member_avatar.roomId = rooms.roomId AND rowNum = 1
LEFT JOIN member_count ON member_count.roomId = rooms.roomId;
"""

// language=sql
const val CLEAR_SYNC_CACHE = """
BEGIN;
DELETE FROM key_value_store WHERE key = 'SYNC_TOKEN';
DELETE FROM room_events;
DELETE FROM room_pagination_tokens;
DELETE FROM room_metadata;
DELETE FROM account_data;
END;
"""
