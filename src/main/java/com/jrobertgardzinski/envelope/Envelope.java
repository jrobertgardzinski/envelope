package com.jrobertgardzinski.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One event as it came off a topic, read in a way that cannot wedge the topic behind it.
 *
 * <p>Everything here answers the same question — <em>what do I do with a record I cannot use?</em>
 * — and the answer is never "throw". A listener that throws on a malformed record gets it
 * redelivered, throws again, and stops the partition: one bad event silently holds up every good
 * one behind it, for ever. So a record that no retry could fix is REFUSED, and a refusal is a
 * {@link Result} with a code in {@code errors()} — the caller logs it and commits past.
 *
 * <p>The distinction this type exists to keep, learned the hard way: <strong>absent is not the same
 * as mangled</strong>. A field that is missing usually means an older producer, and degrading
 * gracefully is right. A field that is PRESENT and unreadable means a producer that wrote it and
 * got it wrong, and degrading there re-opens by the back door exactly the hole the field was added
 * to close. {@link #optionalUuid} keeps them apart; nothing else in this library blurs them.
 *
 * <p>Every refusal carries a code, and {@link #summary()} is what may accompany it in a log: the
 * type, the id, and a masked hint of any address. Never the payload.
 */
public final class Envelope {

    /** The payload was not JSON at all — no retry fixes bytes. */
    public static final String NOT_JSON = "NOT_JSON";
    /** A required field is absent, empty, or blank. */
    public static final String BLANK = "_BLANK";
    /** A required identifier is absent. */
    public static final String MISSING = "_MISSING";
    /** An identifier is present and is not one. */
    public static final String MANGLED = "_MANGLED";
    /** An optional object was larger than the caller allows; the event is used without it. */
    public static final String OVERSIZED = "_OVERSIZED";

    /**
     * What every read here answers: the value, present only when nothing refused it ({@code null}
     * otherwise); the refusals, one code each; and the warnings, for a value that is used minus
     * something. The gate is {@code errors().isEmpty()}.
     */
    public record Result<T>(T value, List<String> errors, List<String> warnings) {
        public Result {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }

    private static <T> Result<T> allowed(T value) {
        return new Result<>(value, List.of(), List.of());
    }

    private static <T> Result<T> refused(String code) {
        return new Result<>(null, List.of(code), List.of());
    }

    private final JsonNode event;
    private final ObjectMapper mapper;

    private Envelope(JsonNode event, ObjectMapper mapper) {
        this.event = event;
        this.mapper = mapper;
    }

    /**
     * Reads a payload, or refuses it. The caller's mapper is used rather than one of ours, because
     * a service that has configured its own is entitled to be read by it.
     */
    public static Result<Envelope> read(String payload, ObjectMapper mapper) {
        try {
            return allowed(new Envelope(mapper.readTree(payload), mapper));
        } catch (Exception notJson) {
            return refused(NOT_JSON);
        }
    }

    /** The {@code type} field every event in this estate carries; empty when it has none. */
    public String type() {
        return event.path("type").asText();
    }

    /** The raw node, for the fields a caller reads on its own terms. */
    public JsonNode node() {
        return event;
    }

    /** A required, non-blank string — an address, a name, a topic. */
    public Result<String> requiredText(String field) {
        String value = event.path(field).asText();
        return value.isBlank()
                ? refused(field.toUpperCase() + BLANK)
                : allowed(value);
    }

    /**
     * A required identifier. Absent and mangled are both refusals here — a caller that says
     * "required" has no fallback in mind — but they get different codes, because "the producer
     * forgot the field" and "the producer wrote nonsense into it" send an operator to different
     * places.
     */
    public Result<UUID> requiredUuid(String field) {
        if (!event.hasNonNull(field)) {
            return refused(field.toUpperCase() + MISSING);
        }
        try {
            return allowed(UUID.fromString(event.get(field).asText()));
        } catch (IllegalArgumentException mangled) {
            return refused(field.toUpperCase() + MANGLED);
        }
    }

    /**
     * An identifier that may legitimately be absent — and <strong>only</strong> absent.
     *
     * <p>Empty means an older producer, and the caller degrades to whatever it did before the field
     * existed. A value that is present and unreadable is REFUSED, not degraded: the producer wrote
     * the field and got it wrong, so the record is mangled rather than old, and treating it as old
     * would let a broken correlation land on whatever case happened to be running.
     */
    public Result<Optional<UUID>> optionalUuid(String field) {
        if (!event.has(field)) {
            return allowed(Optional.empty());
        }
        try {
            return allowed(Optional.of(UUID.fromString(event.get(field).asText())));
        } catch (IllegalArgumentException mangled) {
            return refused(field.toUpperCase() + MANGLED);
        }
    }

    /**
     * An optional object the caller only ferries — a policy, a set of choices — with a ceiling on
     * how big it may be.
     *
     * <p>Over the ceiling the event is still USED, without the field: that is a code in
     * {@code warnings()}, not in {@code errors()}, because an oversized extra is no reason to
     * abandon a deletion. The ceiling itself is not decoration: an object read here is typically
     * stored and re-sent, so an unbounded one rides into a database column and then into every
     * later message.
     */
    public Result<Optional<JsonNode>> objectWithin(String field, int maxBytes) {
        JsonNode value = event.path(field);
        if (!value.isObject()) {
            return allowed(Optional.empty());
        }
        if (serialised(value).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return new Result<>(Optional.empty(), List.of(),
                    List.of(field.toUpperCase() + OVERSIZED));
        }
        return allowed(Optional.of(value));
    }

    /**
     * What may accompany a refusal in a log: what PLACES the record, and nothing that identifies
     * anybody. An address is masked; everything else is left out rather than trusted.
     */
    public String summary() {
        StringBuilder summary = new StringBuilder("type=").append(event.path("type").asText("?"))
                .append(", id=").append(event.path("id").asText("?"));
        String email = event.path("email").asText();
        if (!email.isBlank()) {
            summary.append(", email=").append(Masked.address(email));
        }
        return summary.toString();
    }

    private String serialised(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception impossible) {
            throw new IllegalStateException("could not serialise a node this mapper just read",
                    impossible);
        }
    }
}
