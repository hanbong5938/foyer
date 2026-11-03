package dev.foyer.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code foyer.base-path} is used verbatim as a route prefix, so only one canonical literal form is accepted. */
class FoyerPropertiesTest {

    @Test
    void defaultBasePathIsFoyer() {
        assertThat(new FoyerProperties().getBasePath()).isEqualTo("/foyer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/foyer", "/queue/foyer-v2", "/a/b_c/D-9", "/x"})
    void acceptsLiteralAbsoluteSegments(String path) {
        FoyerProperties properties = new FoyerProperties();
        properties.setBasePath(path);
        assertThat(properties.getBasePath()).isSameAs(path);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "/", "foyer", "/foyer/", "/a//b", "/{x}", "/**", "/a/*", "/a?x", "/a#x", "/a%2Fb", " /foyer", "/foyer ",
            "/fo yer", "/a.b", "//a", "\t", "/foyer\n",
    })
    void rejectsEverythingElseWithoutNormalising(String path) {
        FoyerProperties properties = new FoyerProperties();
        assertThatThrownBy(() -> properties.setBasePath(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("foyer.base-path must be an absolute non-root path with segments containing only letters, "
                        + "digits, underscores and hyphens, and no trailing slash");
        assertThat(properties.getBasePath()).as("rejected value must not be stored").isEqualTo("/foyer");
    }
}
