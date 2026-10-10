package com.adelylria.ringlog.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;

/** Bounded, subsampled image decoding without ImageIO's host temporary-file cache. */
public final class PhotoImages {
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_PIXELS = 80_000_000L;

    private PhotoImages() { }

    public static Preview load(Path file, int maxWidth, int maxHeight) throws IOException {
        if (maxWidth < 1 || maxHeight < 1) throw new IllegalArgumentException("Invalid preview size");
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("Selecciona una imagen JPG o PNG de hasta 50 MB.");
        }
        try (var input = new FileImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("El archivo no es una imagen válida.");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                    throw new IOException("La imagen supera el límite de 80 megapíxeles.");
                }
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, (int) Math.ceil(Math.max(
                        (double) width / maxWidth, (double) height / maxHeight)));
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage preview = reader.read(0, parameters);
                if (preview == null) throw new IOException("No se pudo leer la fotografía.");
                return new Preview(preview, format.equals("jpeg") ? "jpg" : format);
            } finally {
                reader.dispose();
            }
        }
    }

    public record Preview(BufferedImage image, String extension) {
        public String mimeType() { return extension.equals("jpg") ? "image/jpeg" : "image/" + extension; }
    }
}
