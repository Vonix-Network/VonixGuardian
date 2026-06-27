package network.vonix.guardian.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IpHasherTest {

    private static final String SALT_A = "vonix-guardian-default-salt-CHANGE-ME";
    private static final String SALT_B = "another-secret-salt-1234567890";

    @Test
    @DisplayName("null and empty input return empty string")
    void nullAndEmptyReturnEmpty() {
        assertThat(IpHasher.hash(null, SALT_A)).isEmpty();
        assertThat(IpHasher.hash("", SALT_A)).isEmpty();
    }

    @Test
    @DisplayName("same input + same salt -> same hash (deterministic)")
    void deterministic() {
        String a = IpHasher.hash("10.0.0.5", SALT_A);
        String b = IpHasher.hash("10.0.0.5", SALT_A);
        assertThat(a).isEqualTo(b).isNotEmpty();
    }

    @Test
    @DisplayName("different salts produce different hashes for the same input")
    void differentSaltsDifferentHashes() {
        String a = IpHasher.hash("10.0.0.5", SALT_A);
        String b = IpHasher.hash("10.0.0.5", SALT_B);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("output format is sha256:<16 lowercase hex chars>")
    void outputFormat() {
        String h = IpHasher.hash("192.168.1.1", SALT_A);
        assertThat(h).matches("^sha256:[0-9a-f]{16}$");
    }

    @Test
    @DisplayName("tampering with the salt changes the output")
    void saltTamperingChangesOutput() {
        String original = IpHasher.hash("client.example.com", SALT_A);
        String tampered = IpHasher.hash("client.example.com", SALT_A + "x");
        assertThat(tampered).isNotEqualTo(original);
    }
}
