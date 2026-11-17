package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class WebSettingsTest {
    @Test
    void givenJarDefaults_whenRead_thenDisabledEmbeddedOnLoopback() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            config.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        WebSettings s = WebSettings.from(config.getConfigurationSection("web"));
        assertFalse(s.enabled());
        assertEquals(WebSettings.Mode.EMBEDDED, s.mode());
        assertEquals("127.0.0.1", s.bind());
        assertEquals(8190, s.port());
        assertEquals(60_000, s.loginTokenMs());
        assertEquals(30 * 60_000, s.sessionIdleMs());
        assertEquals(12 * 3_600_000L, s.sessionMaxMs());
        assertTrue(s.problems().isEmpty());
        assertFalse(WebSettings.defaults().enabled());
    }

    @Test
    void givenGatewayModeWithoutUrlOrSecret_whenValidated_thenProblems() throws Exception {
        WebSettings s = WebFixture.settings("enabled: true\nmode: gateway\ngateway:\n  url: \"\"\n");
        List<String> problems = s.problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("public-url")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("gateway.url")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("secret")));
    }

    @Test
    void givenInsecureGatewayUrl_whenValidated_thenOnlyLoopbackOrExplicitlyAllowed() throws Exception {
        String secret = "s".repeat(40);
        assertFalse(WebFixture.settings("mode: gateway\npublic-url: https://w.example\ngateway:\n  url: ws://10.0.0.5:8443/t\n  secret: " + secret).problems().isEmpty());
        assertTrue(WebFixture.settings("mode: gateway\npublic-url: https://w.example\ngateway:\n  url: ws://127.0.0.1:8443/t\n  secret: " + secret).problems().isEmpty());
        assertTrue(WebFixture.settings("mode: gateway\npublic-url: https://w.example\ngateway:\n  url: ws://100.64.0.5:8443/t\n  secret: " + secret + "\n  allow-insecure: true").problems().isEmpty());
        assertTrue(WebFixture.settings("mode: gateway\npublic-url: https://w.example\ngateway:\n  url: wss://w.example/t\n  secret: " + secret).problems().isEmpty());
    }

    @Test
    void givenPublicUrlWithPathOrQuery_whenValidated_thenRejected() throws Exception {
        assertFalse(WebFixture.settings("public-url: https://w.example/web").problems().isEmpty());
        assertFalse(WebFixture.settings("public-url: https://w.example/?x=1").problems().isEmpty());
        assertFalse(WebFixture.settings("public-url: ftp://w.example").problems().isEmpty());
        assertTrue(WebFixture.settings("public-url: https://w.example/").problems().isEmpty(), "trailing slash is fine");
    }

    @Test
    void givenPublicUrl_whenOriginAndHostsDerived_thenDefaultPortsStripped() throws Exception {
        WebSettings s = WebFixture.settings("public-url: https://Web.Example.net:443\nallowed-hosts: [alt.example.net]");
        assertEquals("https://web.example.net", s.publicOrigin());
        assertTrue(s.https());
        assertTrue(s.hostAllowlist().contains("web.example.net"));
        assertTrue(s.hostAllowlist().contains("alt.example.net"));
        assertFalse(s.hostAllowlist().contains("127.0.0.1:8190"), "loopback only without public-url");
        WebSettings local = WebFixture.settings("embedded:\n  port: 9000");
        assertEquals("http://127.0.0.1:9000", local.effectivePublicUrl());
        assertTrue(local.hostAllowlist().contains("localhost:9000"));
    }

    @Test
    void givenSecret_whenPrinted_thenNeverContained() throws Exception {
        String secret = "topsecret-" + "x".repeat(40);
        WebSettings s = WebFixture.settings("gateway:\n  secret: " + secret);
        assertFalse(s.toString().contains(secret));
    }

    @Test
    void givenOutOfRangeValues_whenRead_thenClamped() throws Exception {
        WebSettings s = WebFixture.settings("limits:\n  max-body-bytes: 999999999\n  header-timeout-seconds: 0\nsession:\n  login-token-seconds: 1");
        assertEquals(1024 * 1024, s.maxBodyBytes());
        assertEquals(1000, s.headerTimeoutMs());
        assertEquals(10_000, s.loginTokenMs());
    }
}
