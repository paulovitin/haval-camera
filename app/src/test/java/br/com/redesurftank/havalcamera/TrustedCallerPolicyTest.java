package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TrustedCallerPolicyTest {
    @Test public void onlyExactEcoTripPackageAndCertificateAreAllowed() {
        assertTrue(TrustedCallerPolicy.allows("br.com.redesurftank.ecotrip",
                "c380e4bc34c564238d114d826d08fc26d3fa6e651103a7771073de6576921b03"));
        assertFalse(TrustedCallerPolicy.allows("br.com.redesurftank.ecotrip", "00"));
        assertFalse(TrustedCallerPolicy.allows("other.app",
                TrustedCallerPolicy.ECOTRIP_CERT_SHA256));
        assertFalse(TrustedCallerPolicy.allows("br.com.redesurftank.ecotrip", null));
    }
}
