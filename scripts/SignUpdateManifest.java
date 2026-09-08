import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.nio.file.StandardOpenOption;

/** Signs exact manifest bytes and verifies the result before publishing the detached signature. */
public final class SignUpdateManifest {

    private SignUpdateManifest() {
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 4,
                "Usage: java scripts/SignUpdateManifest.java <private.pk8> <public.der> <manifest.json> <manifest.sig>");
        Path privatePath = Path.of(args[0]).toAbsolutePath().normalize();
        Path publicPath = Path.of(args[1]).toAbsolutePath().normalize();
        Path manifestPath = Path.of(args[2]).toAbsolutePath().normalize();
        Path signaturePath = Path.of(args[3]).toAbsolutePath().normalize();
        require(!Files.exists(signaturePath), "Refusing to overwrite an existing signature");

        KeyFactory keys = KeyFactory.getInstance("Ed25519");
        var privateKey = keys.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privatePath)));
        var publicKey = keys.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicPath)));
        byte[] manifest = Files.readAllBytes(manifestPath);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(manifest);
        byte[] detached = signer.sign();
        require(detached.length == 64, "Unexpected Ed25519 signature length");

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(manifest);
        require(verifier.verify(detached), "Private and public update keys do not match");
        Files.createDirectories(signaturePath.getParent());
        Files.write(signaturePath, detached, StandardOpenOption.CREATE_NEW);
        System.out.println(signaturePath);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
