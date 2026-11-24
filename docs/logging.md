# Application logging

The log records the current app process only. There is no automatic disk storage
or history across process restarts. Opening or closing the log screen, or exiting
the app, does not clear entries.

## Entries

- ACTION: device/offline selection (user or saved preference), manual reconnect,
  recording commands, completed file operations, background-spectrum operations,
  preference changes made on the settings screen, and app foreground/background
  transitions.
- EVENT: connection outcomes, recording suspension/resumption, recovery, warnings,
  and failures. Severity is INFO, WARNING, or ERROR, independently of entry type.
- DETAIL: protocol traffic, command responses, retry internals, and rendering
  details.

Exceptions are logged as EVENT/ERROR entries that include the stack trace, so
the trace is kept even when diagnostics capture is off.

## Controls

The log screen's overflow menu contains two independent checkboxes:

- Capture diagnostics: off by default. When enabled, new DETAIL entries are
  retained. Turning it off leaves previously captured details intact.
- Show details: off by default. Changes only which retained entries are displayed.

Both preferences survive app restarts. Errors are always retained as EVENT/ERROR
entries, even when diagnostics are disabled. Troubleshooting: enable capture,
reproduce the problem, save the log, then disable capture.

## Retention and export

ACTION/EVENT entries and DETAIL entries have separate in-memory buffers. Each
holds at most 1,000 entries and 2 MiB of UTF-8 message payload, excluding Java
object overhead. Individual entries are capped at 16 KiB and marked when
truncated. Oldest entries are evicted within the affected buffer; diagnostic
traffic cannot evict ACTION/EVENT entries.

Save log uses the Android destination picker and writes readable UTF-8 text. After
destination selection, it snapshots all retained entries, including hidden
details, and merges them in insertion order. Entries arriving after that snapshot
are excluded. If older entries were evicted, the export reports their counts.
Evicted entries cannot be recovered or exported in this stage.

Foreground/background tracking spans all app activities and excludes ordinary
configuration recreation and document-picker round trips.