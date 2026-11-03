package br.com.redesurftank.havalcamera;

import java.util.Locale;

/** Exact package and signing-certificate allowlist for separately signed local clients. */
final class TrustedCallerPolicy {
    static final String ECOTRIP_PACKAGE = "br.com.redesurftank.ecotrip";
    static final String ECOTRIP_CERT_SHA256 =
            "c380e4bc34c564238d114d826d08fc26d3fa6e651103a7771073de6576921b03";

    static boolean allows(String packageName, String certificateSha256) {
        return ECOTRIP_PACKAGE.equals(packageName)
                && certificateSha256 != null
                && ECOTRIP_CERT_SHA256.equals(certificateSha256.toLowerCase(Locale.US));
    }

    private TrustedCallerPolicy() { }
}
