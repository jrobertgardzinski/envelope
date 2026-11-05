# envelope

Reading an event off a topic **without letting a bad one wedge the topic behind it**, and saying
what was wrong with it **without putting somebody's address in the log**.

Those two are one job. Every refusal here ends in a log line, and a log line about a rejected event
is exactly where an e-mail address escapes into a system with its own retention — usually longer
than the database's, and always outside the reach of a deletion request.

```java
Envelope.Result<Envelope> read = Envelope.read(payload, mapper);
if (!read.errors().isEmpty()) {
    LOG.warn("dropping an unreadable event ({}): {}", Masked.sizeOf(payload), read.errors());
    return;                       // committed past, never thrown
}
Envelope event = read.value();

Envelope.Result<String> email  = event.requiredText("email");
Envelope.Result<UUID>   id     = event.requiredUuid("id");        // ID_MISSING vs ID_MANGLED
Envelope.Result<Optional<UUID>> sagaId = event.optionalUuid("sagaId");
```

## Never throw

A listener that throws on a malformed record gets it redelivered, throws again, and stops the
partition. One bad event silently holds up every good one behind it, for ever. So a record no retry
could fix is **refused**, and a refusal is a `Result` with a code in `errors()` — the caller logs
it and commits past. A `Result` is a record: `value()` (null when refused), `errors()`, `warnings()`.

## Absent is not mangled

The distinction the whole library exists to keep:

- a field that is **missing** usually means an older producer, and degrading gracefully is right;
- a field that is **present and unreadable** means a producer that wrote it and got it wrong.

Degrading there re-opens by the back door exactly the hole the field was added to close — a broken
correlation landing on whatever case happens to be running. `optionalUuid` keeps them apart;
nothing else here blurs them.

## Over the ceiling is not a refusal

`objectWithin(field, maxBytes)` answers with a code in `warnings()`, not in `errors()`: the event
is used, minus the field that did not fit. An oversized extra is no reason to abandon a deletion. The ceiling is not
decoration — an object read here is typically stored and re-sent, so an unbounded one rides into a
database column and then into every later message.

## Masked

What may reach a log about a person: `address` (`le***@example.com`), `path` (for routes that name
somebody, encoded `@` included), `scrubbed` (for text of unknown shape), and `sizeOf` — because an
unparsed payload is described by its size and nothing else. Quoting it back is the worst case:
unknown shape, and a newline in it becomes a new log LINE, so a forged `ERROR` can be planted in an
operator's view.
