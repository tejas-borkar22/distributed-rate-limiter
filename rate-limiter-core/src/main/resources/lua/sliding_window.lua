-- KEYS[1] = zset key for this client
-- ARGV[1] = capacity
-- ARGV[2] = window seconds

    local zset_key = KEYS[1]
    local capacity = tonumber(ARGV[1])
    local window_ms = tonumber(ARGV[2]) * 1000

-- step 1: read Redis's clock (same TIME pattern as Token Bucket)
-- TIME returns two strings: unix seconds, and microseconds into that second.
    local redis_time = redis.call('TIME')
    local now_ms = tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)

-- step 2: evict expired entries
--   ZREMRANGEBYSCORE zset_key 0 (now_ms - window_ms)
--   removes every member scored below the cutoff
    local cutoff = now_ms - window_ms
    redis.call('ZREMRANGEBYSCORE', zset_key, 0, cutoff)

-- step 3: count what's left ZCARD zset_key
    local count = redis.call('ZCARD', zset_key)

-- step 4: if count < capacity:
    if count < capacity then
        --   add this request: ZADD zset_key now_ms (now_ms .. '-' .. math.random(1000000))
        local member = now_ms .. '-' .. math.random(1000000)
        redis.call('ZADD', zset_key, now_ms, member)
        --   set expiry: PEXPIRE zset_key window_ms
        redis.call('PEXPIRE', zset_key, window_ms)
        return { 1, capacity - count - 1, 0 }
    else
        redis.call('PEXPIRE', zset_key, window_ms)
        --   oldest = ZRANGE zset_key 0 0 WITHSCORES  (gives member + score of the oldest entry)
        local oldest = redis.call('ZRANGE', zset_key, 0, 0, 'WITHSCORES')
        --   resetAt-equivalent: oldest_score + window_ms, retry_after_ms = that minus now_ms
        local oldest_score = tonumber(oldest[2])
        local retry_after_ms = (oldest_score + window_ms) - now_ms
        return { 0, 0, retry_after_ms }
    end