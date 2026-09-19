-- A bounded snapshot: no update can interleave rank and score reads.
local op, order = ARGV[1], ARGV[2]
local rankCommand = order == 'desc' and 'ZREVRANK' or 'ZRANK'
if op == 'player' then
    local rank = redis.call(rankCommand, KEYS[1], ARGV[3])
    if not rank then return {} end
    return {ARGV[3], redis.call('ZSCORE', KEYS[1], ARGV[3]), tostring(rank + 1)}
end
local start, stop
if op == 'neighbors' then
    local rank = redis.call(rankCommand, KEYS[1], ARGV[3])
    if not rank then return {} end
    local radius = tonumber(ARGV[4])
    start, stop = math.max(0, rank - radius), rank + radius
else
    start = tonumber(ARGV[3])
    stop = start + tonumber(ARGV[4]) - 1
end
local rows
if order == 'desc' then
    rows = redis.call('ZRANGE', KEYS[1], start, stop, 'REV', 'WITHSCORES')
else
    rows = redis.call('ZRANGE', KEYS[1], start, stop, 'WITHSCORES')
end
local result = {}
for i = 1, #rows, 2 do
    result[#result + 1] = rows[i]
    result[#result + 1] = rows[i + 1]
    result[#result + 1] = tostring(start + (i + 1) / 2)
end
return result
