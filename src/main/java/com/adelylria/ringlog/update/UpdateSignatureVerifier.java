package com.adelylria.ringlog.update;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;

/** Verifies detached Ed25519 signatures over the exact manifest bytes. */
public final class UpdateSignatureVerifier {

    private static final int ED25519_SIGNATURE_BYTES = 64;

    private final PublicKey publicKey;

    private UpdateSignatureVerifier(PublicKey publicKey) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    }

    public static UpdateSignatureVerifier fromX509(byte[] encoded) throws UpdateException {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            return new UpdateSignatureVerifier(key);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new UpdateException("La clave pública de actualizaciones no es válida.", exception);
        }
    }

    public boolean verify(byte[] manifest, byte[] detachedSignature) throws UpdateException {
        if (manifest == null || detachedSignature == null
                || detachedSignature.length != ED25519_SIGNATURE_BYTES) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(manifest);
            return verifier.verify(detachedSignature);
        } catch (GeneralSecurityException exception) {
            throw new UpdateException("No se pudo verificar la firma de actualización.", exception);
        }
    }
}
