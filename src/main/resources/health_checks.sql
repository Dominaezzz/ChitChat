-- Check that timelineOrder is contiguous
SELECT roomId, eventId, timelineId, timelineOrder, rowNum
FROM (
    SELECT ROW_NUMBER() OVER (PARTITION BY roomId, timelineId ORDER BY timelineOrder ASC) AS rowNum, *
    FROM room_events
    WHERE timelineOrder IS NOT NULL
)
WHERE timelineOrder != rowNum;

-- Check that the latest state is marked properly.
WITH
     event_latest(roomId, eventId, latestEventId) AS (
         SELECT roomId, eventId, FIRST_VALUE(eventId) OVER (PARTITION BY roomId, type, stateKey ORDER BY timelineId ASC, timelineOrder DESC)
         FROM room_events
         WHERE stateKey IS NOT NULL
     )
SELECT *
FROM room_events
JOIN event_latest USING (roomId, eventId)
WHERE isLatestState != (eventId == latestEventId)
ORDER BY roomId, type, stateKey, timelineId ASC, timelineOrder DESC;
