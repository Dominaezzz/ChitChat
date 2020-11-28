-- Check that all timeline state events have prevContents
SELECT roomId, type, stateKey, eventId, rowNum
FROM (
    SELECT ROW_NUMBER() OVER (PARTITION BY roomId, type, stateKey ORDER BY timelineId DESC, timelineOrder ASC) AS rowNum, *
    FROM room_events
    WHERE stateKey IS NOT NULL
)
WHERE timelineOrder IS NOT NULL AND prevContent IS NULL AND rowNum != 1;

-- Check that timelineOrder is contiguous
SELECT roomId, eventId, timelineId, timelineOrder, rowNum
FROM (
    SELECT ROW_NUMBER() OVER (PARTITION BY roomId, timelineId ORDER BY timelineOrder ASC) AS rowNum, *
    FROM room_events
    WHERE timelineOrder IS NOT NULL
)
WHERE timelineOrder != rowNum;
