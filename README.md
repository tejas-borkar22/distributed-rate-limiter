# distributed-rate-limiter
Distributed rate limiter in Java and Spring Boot, using Redis and Lua for atomic limits


### Design decision: `X-RateLimit-Reset` precision is estimate-on-allow, exact-on-deny

`X-RateLimit-Reset` is a Unix timestamp. Precision is deliberately different for
allowed vs denied responses, and differs slightly by strategy:

| Strategy                 | On allow (200)              | On deny (429)                    |
|---------------------------|------------------------------|------------------------------------|
| In-memory Token Bucket    | now + window                | now + window                      |
| In-memory Sliding Window  | oldest request + window     | oldest request + window           |
| Redis Token Bucket        | now + window (estimate)     | now + exact wait (precise)        |
| Redis Sliding Window      | now + window (estimate)     | now + exact wait (precise)        |

**Why:** on a 429, the client needs to know exactly when they can retry, so
`Reset` is computed precisely from the algorithm's real internal state. On a
200, no retry is needed — `Reset` is a conservative upper bound (worst case:
the full window), not the exact moment capacity would next change. In-memory
strategies happen to use the same anchor for both cases; the Redis strategies
intentionally don't compute the precise value on the allow path, since it adds
cost for a header the client doesn't act on when the request already succeeded.

**Guidance:** use `Retry-After` to decide when to retry after a 429 — it's
exact for every strategy. Don't compare `Reset` values across allow and deny.