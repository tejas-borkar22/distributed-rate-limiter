-- KEYS[1] = the Redis key for this client's bucket (e.g. "bucket:client-a")
-- ARGV[1] = capacity (max tokens)
-- ARGV[2] = window seconds
-- The current time is read from Redis itself (see below), not passed in.

    local bucket_key = KEYS[1]
    local capacity = tonumber(ARGV[1])
    local window_ms = tonumber(ARGV[2]) * 1000

-- step 1: read Redis's clock.
-- TIME returns two strings: unix seconds, and microseconds into that second.
    local redis_time = redis.call('TIME')
    local now_ms = tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)

-- step 2: HMGET / default-value logic
--   last_refill now_ms holds milliseconds, so the default for a new bucket is now_ms.
    local currentBucketData = redis.call('HMGET', bucket_key, 'tokens', 'last_refill')
	local tokens = currentBucketData[1]
	local last_refill = currentBucketData[2]

	if tokens == false then
		tokens = capacity
	else
		tokens = tonumber(tokens)
	end

	if last_refill == false then
        last_refill = now_ms
	else
		last_refill = tonumber(last_refill)
	end

-- step 3: the refill math, now_ms per millisecond.
	local tokens_per_ms = capacity / window_ms
	local elapsed = now_ms -  last_refill
	local new_tokens = math.min(capacity, tokens + elapsed * tokens_per_ms)


-- step 4: allow or deny, then SAVE and EXPIRE.
--   After each HSET, add one line: redis.call('PEXPIRE', bucket_key, window_ms)
--   PEXPIRE is the millisecond version of "delete this key after N".

    -- step 5: return a table : { allowed (1 or 0), remaining tokens (round down), retry_after_ms }
    --   For an allowed request, retry_after_ms is 0.
    if new_tokens >= 1 then
		new_tokens = new_tokens - 1
		redis.call('HSET', bucket_key, 'tokens', new_tokens, 'last_refill', now_ms)
		redis.call('PEXPIRE', bucket_key, window_ms)
        return { 1, math.floor(new_tokens), 0 }

    --   For a denied one: how many ms until the bucket reaches 1 toke n?
           --   You need (1 - new_tokens) divided by tokens_per_ms.
           --   Round UP with math.ceil, the same lesson as the sliding window.
	else
		redis.call('HSET', bucket_key, 'tokens', new_tokens, 'last_refill', now_ms)
		redis.call('PEXPIRE', bucket_key, window_ms)
        -- resetAt = now + time until ONE token becomes available
		local retry_after_ms = math.ceil((1 - new_tokens) / tokens_per_ms)
		return { 0, 0, retry_after_ms }
    end