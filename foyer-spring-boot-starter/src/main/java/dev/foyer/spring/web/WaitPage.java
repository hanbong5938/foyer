package dev.foyer.spring.web;

import dev.foyer.spring.gate.AdmissionGate;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the bundled wait page with room, queue endpoint base (context path + base path) and redirect target
 * injected as JSON string literals. The template is split at its placeholders once, so a substituted value can
 * never be re-scanned for another placeholder (a room named {@code __REDIRECT__} must not open an injection).
 */
public final class WaitPage {

    private static final String[] PLACEHOLDERS = {"__ROOM__", "__BASE__", "__REDIRECT__"};
    /** Template parts in order; odd indices are placeholder positions (an index into {@link #PLACEHOLDERS}). */
    private static final List<Object> PARTS = split(load());

    private final String basePath;

    public WaitPage(String basePath) {
        this.basePath = basePath;
    }

    public String render(String contextPath, String room, String redirect) {
        String[] values = {
            AdmissionGate.jsonString(room),
            AdmissionGate.jsonString(contextPath + basePath),
            AdmissionGate.jsonString(AdmissionGate.safeRedirect(contextPath, redirect)),
        };
        StringBuilder sb = new StringBuilder();
        for (Object part : PARTS) {
            sb.append(part instanceof Integer i ? values[i] : (String) part);
        }
        return sb.toString();
    }

    private static List<Object> split(String template) {
        List<Object> parts = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = -1, which = -1;
            for (int i = 0; i < PLACEHOLDERS.length; i++) {
                int idx = template.indexOf(PLACEHOLDERS[i], from);
                if (idx >= 0 && (at < 0 || idx < at)) { at = idx; which = i; }
            }
            if (at < 0) break;
            parts.add(template.substring(from, at));
            parts.add(which);
            from = at + PLACEHOLDERS[which].length();
        }
        parts.add(template.substring(from));
        return List.copyOf(parts);
    }

    private static String load() {
        try (InputStream in = WaitPage.class.getResourceAsStream("wait.html")) {
            if (in == null) throw new IllegalStateException("missing wait.html");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
