package Vystorm.vystorm_core.network.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KeyMaterialTest {
    @TempDir Path dir;

    @Test
    void givenRfc5869Case1WhenDerivedThenOutputMatches() {
        HexFormat hex = HexFormat.of();
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] okm = Hkdf.derive(ikm, hex.parseHex("000102030405060708090a0b0c"), hex.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42);
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex.formatHex(okm));
    }

    @Test
    void givenSameVelocitySecretWhenDerivedTwiceThenKeysMatch() {
        assertEquals(KeySources.fromVelocitySecret("abc123"), KeySources.fromVelocitySecret(" abc123\n"));
        assertNotEquals(KeySources.fromVelocitySecret("abc123"), KeySources.fromVelocitySecret("abc124"));
    }

    @Test
    void givenPaperGlobalWithSecretWhenReadThenSecretFound() throws Exception {
        Path file = dir.resolve("paper-global.yml");
        Files.writeString(file, """
                _version: 31
                proxies:
                  bungee-cord:
                    online-mode: true
                  velocity:
                    enabled: true
                    online-mode: true
                    secret: 'hunter2' # comment
                scoreboards:
                  secret: other
                """);
        assertEquals(Optional.of("hunter2"), KeySources.readPaperVelocitySecret(file));
    }

    @Test
    void givenVelocityDisabledWithLeftoverSecretWhenReadThenEmpty() throws Exception {
        Path file = dir.resolve("paper-global.yml");
        Files.writeString(file, "proxies:\n  velocity:\n    enabled: false\n    secret: leftover\n");
        assertEquals(Optional.empty(), KeySources.readPaperVelocitySecret(file));
    }

    @Test
    void givenMissingPaperGlobalWhenReadThenEmpty() {
        assertEquals(Optional.empty(), KeySources.readPaperVelocitySecret(dir.resolve("missing.yml")));
    }

    @Test
    void givenEmptyVelocitySecretWhenReadThenEmpty() throws Exception {
        Path file = dir.resolve("paper-global.yml");
        Files.writeString(file, "proxies:\n  velocity:\n    enabled: false\n    secret: ''\n");
        assertEquals(Optional.empty(), KeySources.readPaperVelocitySecret(file));
    }

    @Test
    void givenUnreadablePaperGlobalWhenReadThenEmpty() throws Exception {
        Path directoryInstead = Files.createDirectory(dir.resolve("paper-global.yml"));
        assertEquals(Optional.empty(), KeySources.readPaperVelocitySecret(directoryInstead));
    }

    @Test
    void givenVelocityTomlWhenForwardingFileConfiguredThenResolvedAgainstProxyDir() throws Exception {
        Files.writeString(dir.resolve("velocity.toml"), "player-info-forwarding-mode = \"modern\"\nforwarding-secret-file = \"secret.txt\"\n");
        Files.writeString(dir.resolve("secret.txt"), "s3cr3t\n");
        assertEquals(Optional.of("s3cr3t"), KeySources.readVelocityForwardingSecret(dir.resolve("velocity.toml"), dir));
    }

    @Test
    void givenNoTomlSettingWhenReadThenDefaultFileUsed() throws Exception {
        Files.writeString(dir.resolve("forwarding.secret"), "abc");
        assertEquals(Optional.of("abc"), KeySources.readVelocityForwardingSecret(dir.resolve("velocity.toml"), dir));
    }

    @Test
    void givenKeyThenFingerprintIsEightHexAndToStringHidesKey() {
        NetworkKey key = KeySources.fromVelocitySecret("x");
        assertTrue(key.fingerprint().matches("[0-9a-f]{8}"));
        String b64 = Base64.getEncoder().encodeToString(key.bytes());
        assertFalse(key.toString().contains(b64));
        assertFalse(key.toString().contains(HexFormat.of().formatHex(key.bytes())));
    }

    @Test
    void givenSavedKeyWhenLoadedThenEqual() throws Exception {
        NetworkKey key = KeySources.fromVelocitySecret("roundtrip");
        Path file = dir.resolve("network/network.key");
        KeyFile.save(file, key);
        assertEquals(Optional.of(key), KeyFile.load(file));
        KeyFile.delete(file);
        assertEquals(Optional.empty(), KeyFile.load(file));
    }

    @Test
    void givenWrongLengthKeyFileWhenLoadedThenEmpty() throws Exception {
        Path file = dir.resolve("network.key");
        Files.writeString(file, Base64.getEncoder().encodeToString(new byte[16]));
        assertEquals(Optional.empty(), KeyFile.load(file));
    }
}
