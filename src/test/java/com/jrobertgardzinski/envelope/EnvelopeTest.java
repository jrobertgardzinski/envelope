package com.jrobertgardzinski.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading an event without letting a bad one wedge the topic behind it — and the one distinction
 * the whole type exists for: absent is not mangled.
 */
@Epic("Messaging")
@Feature("Reading an envelope")
class EnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ID = UUID.fromString("3b8f0e64-2f1d-4b8b-9a44-6f9a2b1c0d55");

    private static Envelope envelope(String payload) {
        return Envelope.read(payload, MAPPER).value();
    }

    @Test
    @DisplayName("a payload that is not JSON is refused, never thrown")
    void not_json_is_a_refusal() {
        Envelope.Result<Envelope> read = Envelope.read("{not json at all", MAPPER);

        assertEquals(List.of(Envelope.NOT_JSON), read.errors(),
                "a listener that THROWS here gets the record redelivered for ever and stops the"
                        + " partition; one bad event must not hold up every good one behind it");
        assertNull(read.value());
    }

    @Test
    @DisplayName("a required field that is blank is refused by its own name")
    void a_blank_required_field_names_itself() {
        Envelope.Result<String> email = envelope("{\"email\":\"  \"}").requiredText("email");

        assertEquals(List.of("EMAIL_BLANK"), email.errors());
    }

    @Test
    @DisplayName("a required identifier tells absent and mangled apart, and refuses both")
    void a_required_identifier_distinguishes_its_refusals() {
        assertEquals(List.of("ID_MISSING"), envelope("{}").requiredUuid("id").errors());
        assertEquals(List.of("ID_MANGLED"),
                envelope("{\"id\":\"not-an-id\"}").requiredUuid("id").errors(),
                "\"the producer forgot the field\" and \"the producer wrote nonsense into it\""
                        + " send an operator to different places");
        assertEquals(ID, envelope("{\"id\":\"" + ID + "\"}").requiredUuid("id").value());
    }

    @Test
    @DisplayName("an ABSENT optional identifier is allowed — that is an older producer")
    void an_absent_optional_identifier_degrades() {
        Envelope.Result<Optional<UUID>> sagaId = envelope("{\"type\":\"X\"}").optionalUuid("sagaId");

        assertTrue(sagaId.errors().isEmpty());
        assertTrue(sagaId.value().isEmpty(),
                "the caller degrades to whatever it did before the field existed");
    }

    @Test
    @DisplayName("a PRESENT but unreadable optional identifier is refused, not degraded")
    void a_mangled_optional_identifier_is_not_an_old_producer() {
        Envelope.Result<Optional<UUID>> sagaId =
                envelope("{\"sagaId\":\"nonsense\"}").optionalUuid("sagaId");

        assertEquals(List.of("SAGAID_MANGLED"), sagaId.errors(),
                "degrading here would re-open by the back door exactly the hole the field was"
                        + " added to close: a broken correlation landing on whatever case happens"
                        + " to be running");
    }

    @Test
    @DisplayName("an oversized ferried object drops the field and KEEPS the event")
    void an_oversized_object_is_a_warning_not_a_refusal() {
        String big = "{\"policy\":{\"memes\":\"" + "x".repeat(200) + "\"}}";

        Envelope.Result<Optional<com.fasterxml.jackson.databind.JsonNode>> policy =
                envelope(big).objectWithin("policy", 64);

        assertTrue(policy.errors().isEmpty(),
                "an oversized extra is no reason to abandon a deletion");
        assertTrue(policy.value().isEmpty(), "but it does not ride along");
        assertEquals(List.of("POLICY_OVERSIZED"), policy.warnings());
    }

    @Test
    @DisplayName("an object within the ceiling rides along untouched")
    void a_small_object_is_kept() {
        Envelope.Result<Optional<com.fasterxml.jackson.databind.JsonNode>> policy =
                envelope("{\"policy\":{\"memes\":\"DELETE\"}}").objectWithin("policy", 8192);

        assertEquals("DELETE", policy.value().orElseThrow().path("memes").asText());
    }

    @Test
    @DisplayName("the summary places the record and masks the person")
    void the_summary_says_where_not_who() {
        String summary = envelope("{\"type\":\"ACCOUNT_DELETION_REQUESTED\",\"id\":\"" + ID
                + "\",\"email\":\"leaver@example.com\"}").summary();

        assertTrue(summary.contains("ACCOUNT_DELETION_REQUESTED"), summary);
        assertTrue(summary.contains(ID.toString()), summary);
        assertTrue(summary.contains("le***@example.com"), summary);
        assertFalse(summary.contains("leaver@example.com"),
                "logs outlive the data they describe: an address in a WARN about a rejected"
                        + " deletion survives the erasure being carried out — " + summary);
    }
}
