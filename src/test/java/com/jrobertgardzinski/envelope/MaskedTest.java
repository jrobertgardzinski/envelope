package com.jrobertgardzinski.envelope;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** What may reach a log about a person. */
@Epic("Messaging")
@Feature("Keeping people out of logs")
class MaskedTest {

    @Test
    @DisplayName("an address keeps enough to follow one case and no more")
    void an_address_is_recognisable_not_readable() {
        assertEquals("le***@example.com", Masked.address("leaver@example.com"));
    }

    @Test
    @DisplayName("a very short local part gives nothing away rather than most of itself")
    void a_short_address_gives_nothing() {
        assertEquals("***@example.com", Masked.address("ab@example.com"));
        assertEquals("***@example.com", Masked.address("a@example.com"));
    }

    @Test
    @DisplayName("an address in a path is masked; the route stays readable")
    void a_path_keeps_its_route() {
        assertEquals("/account/le***@example.com", Masked.path("/account/leaver@example.com"));
        assertEquals("/admin/users/vi***@example.com/roles",
                Masked.path("/admin/users/victim@example.com/roles"));
        assertEquals("/account/password", Masked.path("/account/password"));
    }

    @Test
    @DisplayName("a percent-encoded @ is masked too — a path arrives either way")
    void an_encoded_at_is_still_an_address() {
        assertEquals("/account/le***@example.com", Masked.path("/account/leaver%40example.com"));
    }

    @Test
    @DisplayName("text of unknown shape keeps its meaning and loses its addresses")
    void scrubbing_keeps_the_text_useful() {
        String scrubbed = Masked.scrubbed("no route to host for leaver@example.com after 3 tries");

        assertEquals("no route to host for *** after 3 tries", scrubbed);
        assertFalse(scrubbed.contains("leaver"));
    }

    @Test
    @DisplayName("an unreadable payload is described by its size alone")
    void a_payload_is_only_ever_a_size() {
        assertEquals("17 chars", Masked.sizeOf("{not json at all}"));
        assertEquals("0 chars", Masked.sizeOf(null));
    }
}
