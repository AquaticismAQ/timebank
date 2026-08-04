package cn.timebank.testsupport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;

/**
 * A genuinely decodable 1x1 JPEG, not just a magic-byte-only fake — B6.12's
 * EXIF-stripping pass (re-encoding every uploaded image via {@code
 * ImageIO}) rejects anything {@code ImageIO.read} can't actually decode,
 * so fixtures used across the file-upload test flow need a real image, not
 * a hand-rolled byte array that merely starts with the right signature.
 */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] validJpegBytes() {
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException(e));
        }
    }
}
