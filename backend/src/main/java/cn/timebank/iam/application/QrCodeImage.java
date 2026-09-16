package cn.timebank.iam.application;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The QR half of a credential slip (10-StudentIdentityAndOnboarding.md §2.5):
 * the activation code wrapped in a deep link, so scanning is one tap.
 *
 * <p>Deliberately thin. zxing's {@code javase} module would supply
 * {@code MatrixToImageWriter} and save this class, but it also pulls in the
 * command-line tooling's dependencies for the sake of the one loop below, so
 * only {@code com.google.zxing:core} is a dependency (see {@code pom.xml}).</p>
 *
 * <p><b>The encoded string is a live credential.</b> Nothing here logs, caches
 * or otherwise retains it — the {@link BufferedImage} returned is the only
 * copy, and it lives exactly as long as the PDF being streamed.</p>
 */
final class QrCodeImage {

    /**
     * Error correction {@code M} (~15% recoverable), not {@code L} and not
     * {@code H}. This code is printed on paper a twelve-year-old carries in a
     * school bag, so a crease or a thumbprint across it is the normal case, not
     * the exceptional one — {@code L} gives too little margin for that.
     * {@code H} would nearly double the module count at this payload length,
     * shrinking each module below what a phone camera resolves at the slip size
     * §2.8's two-per-page layout allows.
     */
    private static final ErrorCorrectionLevel CORRECTION = ErrorCorrectionLevel.M;

    /**
     * Rendered at a fixed module count times this many pixels, then scaled to
     * the PDF's point box. Oversampling rather than drawing at the final size
     * keeps every module an exact whole number of pixels, which is what stops a
     * printer's own resampling from smearing module edges into each other.
     */
    private static final int PIXELS_PER_MODULE = 8;

    private static final int QUIET_ZONE_MODULES = 2;

    private QrCodeImage() {
    }

    /**
     * @param content the activation deep link — {@code <root>/activate?code=...}
     * @return a black-on-white 1-bit-per-pixel-looking RGB image; callers embed
     *         it losslessly, since JPEG artefacts on a QR are exactly the noise
     *         its error correction budget is meant to absorb
     */
    static BufferedImage of(String content) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, CORRECTION,
                    // The payload is Crockford base32 plus an ASCII URL, so this
                    // is only ever a no-op reassurance — stated so a future
                    // non-ASCII payload fails loudly here instead of encoding
                    // under a platform-default charset that a scanner then
                    // decodes differently.
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                    EncodeHintType.MARGIN, QUIET_ZONE_MODULES));
        } catch (WriterException e) {
            // Unreachable for a payload this short; an activation URL is ~60
            // ASCII characters against a version-40 capacity in the thousands.
            // Never include `content` in the message — it is the code.
            throw new IllegalStateException("Could not encode the activation link as a QR code", e);
        }

        int width = matrix.getWidth();
        int height = matrix.getHeight();
        BufferedImage image = new BufferedImage(width * PIXELS_PER_MODULE, height * PIXELS_PER_MODULE,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = matrix.get(x, y) ? 0x000000 : 0xFFFFFF;
                for (int dy = 0; dy < PIXELS_PER_MODULE; dy++) {
                    for (int dx = 0; dx < PIXELS_PER_MODULE; dx++) {
                        image.setRGB(x * PIXELS_PER_MODULE + dx, y * PIXELS_PER_MODULE + dy, rgb);
                    }
                }
            }
        }
        return image;
    }
}
