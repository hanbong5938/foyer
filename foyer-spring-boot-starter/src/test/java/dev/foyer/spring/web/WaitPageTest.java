package dev.foyer.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foyer.spring.gate.AdmissionGate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What ends up inside the wait page's inline script. */
class WaitPageTest {

    @Test
    void placeholdersAreSubstitutedOnceEvenWhenValuesContainPlaceholderNames() {
        // A room id of __REDIRECT__ is valid configuration; the redirect must not be substituted into it.
        String html = new WaitPage("/__BASE__").render("", "__REDIRECT__", "/1\";alert(1);//");
        assertThat(html)
                .contains("var room = \"__REDIRECT__\", base = \"/__BASE__\", redirect = \"/1\\\";alert(1);//\";")
                .doesNotContain("alert(1);//\", base");
    }

    @Test
    void renderMatchesTheNaiveTemplateForOrdinaryValues() {
        String html = new WaitPage("/foyer").render("/app", "demo", "/app/seats?x=1");
        assertThat(html).contains("var room = \"demo\", base = \"/app/foyer\", redirect = \"/app/seats?x=1\";");
    }

    @Test
    void redirectTargetsWithControlCharactersFallBackToTheRoot() {
        for (String bad : List.of("/\t/evil.example", "/\n/evil.example", "/\r/evil.example", "/a\u007fb", "/a\u0000b")) {
            assertThat(AdmissionGate.safeRedirect("/app", bad)).as("%s", bad).isEqualTo("/app/");
        }
        assertThat(AdmissionGate.safeRedirect("/app", "/app/seats?x=1&y=2")).isEqualTo("/app/seats?x=1&y=2");
        assertThat(AdmissionGate.safeRedirect("", "//evil.example")).isEqualTo("/");
        assertThat(AdmissionGate.safeRedirect("", "/\\evil.example")).isEqualTo("/");
    }
}
