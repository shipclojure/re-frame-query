# Status Tracking

The library distinguishes between **initial loading** and **background refetching** so your UI never flashes a loading spinner when stale data is available:

| Scenario                        | `:status`  | `:fetching?` | `:data`                |
|---------------------------------|------------|--------------|------------------------|
| Initial load (no data)          | `:loading` | `true`       | `nil`                  |
| Background refetch (stale data) | `:success` | `true`       | previous data          |
| Idle, fresh data                | `:success` | `false`      | current data           |
| Failed fetch                    | `:error`   | `false`      | previous data or `nil` |
| Retry after error               | `:loading` | `true`       | previous data or `nil` |

Use `:status` for **data state** (what to render) and `:fetching?` for **network state** (show a subtle spinner).
