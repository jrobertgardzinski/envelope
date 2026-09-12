package com.jrobertgardzinski.envelope;

/**
 * What may reach a log about a person, and what may not.
 *
 * <p>The rule this exists to keep is easy to state and easy to forget in the middle of writing an
 * error message: <strong>logs outlive the data they describe</strong>. They are shipped somewhere
 * with its own retention, usually longer than the database's and always outside the reach of a
 * deletion request. An address written into a WARN about a rejected event is an address that
 * survives the erasure the system is carrying out at that very moment.
 *
 * <p>So: never the whole payload (its SIZE is what an investigator needs anyway), never a raw
 * address, and an age rather than an identifier wherever an age will do. What stays is enough to
 * follow one case through the estate — which is the only thing a log is for here.
 */
public final class Masked {

    private Masked() {
    }

    /**
     * An address as it may appear at INFO: enough to recognise one case while reading, not enough
     * to be a record of who was involved. A very short local part gives nothing away at all rather
     * than most of itself.
     */
    public static String address(String address) {
        if (address == null) {
            return null;
        }
        int at = address.indexOf('@');
        if (at <= 2) {
            return "***" + (at < 0 ? "" : address.substring(at));
        }
        return address.substring(0, 2) + "***" + address.substring(at);
    }

    /**
     * A request path with an address in one of its segments — which is what every route that names
     * a person looks like ({@code DELETE /account/leaver@example.com}). The access log copies the
     * path verbatim, so masking here rather than reshaping the URLs is the cheaper half of the
     * choice: the URLs are a published contract, this is one function.
     *
     * <p>A segment counts as an address if it holds an {@code @}, encoded or not — a path arrives
     * either way depending on who decoded it.
     */
    public static String path(String path) {
        if (path == null) {
            return null;
        }
        String decoded = path.replaceAll("(?i)%40", "@");
        if (!decoded.contains("@")) {
            return path;
        }
        String[] segments = decoded.split("/", -1);
        StringBuilder out = new StringBuilder(decoded.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(segments[i].contains("@") ? address(segments[i]) : segments[i]);
        }
        return out.toString();
    }

    /**
     * For text whose shape is unknown — a payload that would not even parse, an exception message
     * from a library. Anything address-shaped goes; everything else stays, because the point is to
     * keep the text useful.
     */
    public static String scrubbed(String text) {
        return text == null ? null : text.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "***");
    }

    /**
     * What to say about a payload that could not be read at all: its size and nothing else. Quoting
     * it back would be the worst case of all — unparsed text of unknown shape, which is precisely
     * when the scrubber has the least idea what it is looking at, and newlines in it become new log
     * LINES, so a forged "ERROR" can be planted in an operator's view.
     */
    public static String sizeOf(String payload) {
        return (payload == null ? 0 : payload.length()) + " chars";
    }
}
