# Garbage Collection

Inactive queries (no subscribers) are automatically garbage-collected after their `cache-time-ms` expires. This uses **per-query timers**, following the same model as TanStack Query and RTK Query:

1. **Component unmounts** → query marked inactive → `setTimeout` starts with `cache-time-ms`
2. **Component remounts before timer fires** → timer is cancelled, query stays in cache
3. **Timer fires** → query removed from `app-db` (only if still inactive)

| Config | Default | Description |
|---|---|---|
| `:cache-time-ms` | `300000` (5 min) | Time before inactive query is GC'd |
| `:stale-time-ms` | none | Time before data is considered stale |

Timer handles are stored in a side-channel atom (not in `app-db`) to keep the re-frame store fully serializable.
