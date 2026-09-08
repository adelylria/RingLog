import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.nio.file.StandardOpenOption;

/** Generates one Ed25519 release key pair without ever printing private material. */
public final class GenerateUpdateSigningKeys {

    private GenerateUpdateSigningKeys() {
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2,
                "Usage: java scripts/GenerateUpdateSigningKeys.java <private.pk8> <public.der>");
        Path privateKey = Path.of(args[0]).toAbsolutePath().normalize();
        Path publicKey = Path.of(args[1]).toAbsolutePath().normalize();
        require(!privateKey.equals(publicKey), "Private and public key paths must differ");
        require(!Files.exists(privateKey) && !Files.exists(publicKey),
                "Refusing to overwrite an existing signing key");
        Files.createDirectories(privateKey.getParent());
        Files.createDirectories(publicKey.getParent());

        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.write(privateKey, pair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
        try {
            Files.write(publicKey, pair.getPublic().getEncoded(), StandardOpenOption.CREATE_NEW);
        } catch (Exception exception) {
            Files.deleteIfExists(privateKey);
            throw exception;
        }
        System.out.println("Ed25519 keys generated. Store the private key offline and backed up.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
