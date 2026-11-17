package Vystorm.vystorm_core.licensing;

import java.net.URI;
import java.security.PublicKey;
import java.util.Objects;

/** Immutable settings supplied by a paid plugin. One license key per customer/product; never a Core master key.
 * @since 0.28.0 */
public final class ModuleRequest {
    final URI endpoint;
    final String product, version, key, packagePrefix, entryPoint;
    final PublicKey signingKey;

    /** The HTTPS endpoint and Ed25519 public key must be pinned by the plugin, not supplied by a server response.
     * The entry point must implement {@link LicensedModule} and have a public no-argument constructor. */
    public ModuleRequest(URI endpoint, String product, String version, String customerKey,
                         PublicKey signingKey, String packagePrefix, String entryPoint) {
        this.endpoint = Objects.requireNonNull(endpoint);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("A plain HTTPS endpoint is required");
        this.product = identifier(product);
        this.version = identifier(version);
        if (customerKey == null || !customerKey.matches("[\\x21-\\x7e]{1,4096}"))
            throw new IllegalArgumentException("Invalid customer key format");
        this.key = customerKey;
        this.signingKey = Objects.requireNonNull(signingKey);
        if (!"Ed25519".equalsIgnoreCase(signingKey.getAlgorithm()) && !"EdDSA".equalsIgnoreCase(signingKey.getAlgorithm()))
            throw new IllegalArgumentException("Ed25519 public key required");
        this.packagePrefix = Objects.requireNonNull(packagePrefix);
        this.entryPoint = Objects.requireNonNull(entryPoint);
        if (!packagePrefix.matches("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+")
                || !entryPoint.startsWith(packagePrefix)
                || !entryPoint.matches("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*"))
            throw new IllegalArgumentException("Invalid module package or entry point");
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.+-]{1,64}"))
            throw new IllegalArgumentException("Invalid product or version");
        return value;
    }

    @Override public String toString() { return "ModuleRequest[product=" + product + ", version=" + version + ", key=REDACTED]"; }
}
