package com.adelylria.ringlog.update;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

public final class UpdateSignatureVerifierTest {

    private UpdateSignatureVerifierTest() {
    }

    public static void exactManifestBytesAreAuthenticated() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] manifest = "{\"version\":\"1.0.1\"}".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(manifest);
        byte[] signature = signer.sign();

        UpdateSignatureVerifier verifier = UpdateSignatureVerifier.fromX509(
                pair.getPublic().getEncoded()
        );
        require(verifier.verify(manifest, signature), "A valid signature must verify");
        manifest[manifest.length - 2] ^= 1;
        require(!verifier.verify(manifest, signature), "Changed manifest bytes must fail verification");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        exactManifestBytesAreAuthenticated();
        System.out.println("UpdateSignatureVerifierTest: PASS");
    }
}
