-- Both keys share a Redis Cluster hash tag. Validate everything before writing.
local player, delta, ttl, bound = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3]), tonumber(ARGV[4])
if redis.call('EXISTS', KEYS[2]) == 1 then
    if redis.call('HGET', KEYS[2], 'player') ~= player or redis.call('HGET', KEYS[2], 'delta') ~= ARGV[2] then
        return {'CONFLICT'}
    end
    return {'OK', redis.call('HGET', KEYS[2], 'score'), 'true'}
end
local current = tonumber(redis.call('ZSCORE', KEYS[1], player) or '0')
if (delta > 0 and current > bound - delta) or (delta < 0 and current < -bound - delta) then
    return {'OUT_OF_RANGE'}
end
local score = redis.call('ZINCRBY', KEYS[1], ARGV[2], player)
redis.call('HSET', KEYS[2], 'player', player, 'delta', ARGV[2], 'score', score)
redis.call('EXPIRE', KEYS[2], ttl)
return {'OK', score, 'false'}
