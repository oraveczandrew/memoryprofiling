# `.asdb` format

> **Stability warning**
>
> This format is reverse-engineered from files produced by Android Studio's
> memory profiler. It is not a published contract. Field meaning, protobuf
> schemas or the database layout may change in any Android Studio or Android
> release. The documentation below describes what this library currently writes
> and what has been observed to work.

## Container

An `.asdb` file is an SQLite database. The writer creates one table:

```sql
CREATE TABLE UnifiedEventsTable (
    StreamId   INTEGER,
    ProcessId  INTEGER,
    GroupId    INTEGER,
    Kind       INTEGER,
    CommandId  INTEGER,
    Timestamp  INTEGER,
    Data       BLOB
);
```

Rows written by this library use `StreamId = 1`, `GroupId = 0`,
`CommandId = 0`, `ProcessId = Process.myPid()`, and a nanosecond
`Timestamp`. There is also an index on `Kind`.

Two row kinds are emitted:

| Kind | Content                                                   |
|------|-----------------------------------------------------------|
| 408  | Context batch: classes, methods, stack traces and threads |
| 409  | Allocation event batch                                    |

One kind 408 row is written when the capture finishes. Kind 409 rows are written
during the capture; each row contains at most 20,000 events.

## Kind 408: contexts

`Data` is a protobuf message:

```
Event
  field 408 (bytes): Contexts
    field 1 (bytes): ContextBatch
      field 1 (varint): timestamp in nanoseconds
      field 2 (bytes, repeated): Class
        field 1 (varint): class id
        field 2 (string): binary class name
      field 3 (bytes, repeated): Method
        field 1 (varint): method id
        field 2 (string): binary class name
        field 3 (string): method name
        field 4 (string): source file
      field 4 (bytes, repeated): Stack
        field 1 (varint): stack id
        field 3 (bytes): frames
          field 1 (bytes, repeated): Frame
            field 1 (varint): method id
            field 2 (varint): line number
      field 5 (bytes, repeated): Thread
        field 2 (varint): thread id
        field 3 (string): thread name
```

## Kind 409: allocation events

`Data` is a protobuf message:

```
Event
  field 409 (bytes): Events
    field 1 (bytes): EventBatch
      field 1 (varint): timestamp in nanoseconds
      field 2 (bytes, repeated): Event
        field 4 (bytes): Allocation
          field 2 (varint): class id (references kind 408)
          field 3 (varint): allocation size in bytes
          field 4 (varint): array length (0 for non-arrays)
          field 5 (varint): thread id (references kind 408)
          field 6 (varint): stack id (references kind 408, omitted when 0)
          field 10 (string): thread name
```

Class ids in allocation events refer to the class table in the kind 408 row, so
a reader must decode kind 408 before resolving class names.

## Native-to-Kotlin buffer protocol

The JVMTI agent does not write SQLite itself. It buffers a compact binary record, 
and a Kotlin writer drains it approximately every 25 ms (and once more when the
capture stops). The buffer is little-endian and consists of length-prefixed
sections in this order:

1. classes: `count`, then repeated `(id, string name)`
2. methods: `count`, then repeated `(id, class id, name, file)`
3. stacks: `count`, then repeated `(id, frame count, frames of (method id, line))`
4. threads: `count`, then repeated `(id, long, name)`
5. allocations: `count`, then repeated
   `(class id, size, length, thread id, stack id, timestamp ns)`
6. a trailing `long` marker

Strings are encoded as an `int` byte length followed by UTF-8 bytes.

## Inspecting a capture

```bash
sqlite3 capture.asdb \
  'SELECT Kind, COUNT(*) FROM UnifiedEventsTable GROUP BY Kind;'
```

To see the raw payloads:

```bash
sqlite3 capture.asdb \
  "SELECT Kind, length(Data) FROM UnifiedEventsTable ORDER BY Kind, Timestamp;"
```
