package cn.timebank.iam.application;

import cn.timebank.iam.application.CredentialSlipDocument.Slip;
import cn.timebank.iam.application.dto.CredentialSlipDtos.SlipsPerPage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 10-StudentIdentityAndOnboarding.md §2.8/§2.9, §3.4.
 *
 * <p>Every assertion here reads the <b>finished PDF</b> — text through
 * {@link PDFTextStripper}, QR payloads by rasterising the page and decoding it
 * the way a phone camera would. Nothing asserts against the builder's
 * intermediate state, which is the whole point: a layout regression that put
 * the provenance line once per page instead of once per slip would leave every
 * intermediate structure intact and only show up in the rendered document.</p>
 */
class CredentialSlipDocumentTest {

    private static final Instant ISSUED = Instant.parse("2026-09-17T09:30:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-09-24T09:30:00Z");
    private static final String ROOT = "https://tb.example.com";

    /**
     * §2.8's rule, and the reason it exists: "the sheet may be cut, and a
     * page-level mark survives on exactly one piece". Two slips share one page
     * here, so a single provenance line per page would still look right in a
     * viewer and be wrong the moment scissors touch it.
     */
    @Test
    void provenanceIsPrintedOnEverySlipNotOncePerPage() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "AAAAAAAA1"),
                slip("22222222", "BBBBBBBB2")), SlipsPerPage.TWO);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages())
                    .as("two slips at two per page is one sheet")
                    .isEqualTo(1);
            assertThat(occurrences(pageText(document, 1), CredentialSlipDocument.PROVENANCE_PREFIX))
                    .as("one provenance line per slip on the page, not one per page")
                    .isEqualTo(2);
        }
    }

    /** The same rule across a multi-page batch: every slip, on every sheet. */
    @Test
    void everySlipOnEveryPageCarriesItsOwnProvenance() throws IOException {
        List<Slip> slips = List.of(slip("11111111", "AAAAAAAA1"), slip("22222222", "BBBBBBBB2"),
                slip("33333333", "CCCCCCCC3"), slip("44444444", "DDDDDDDD4"));
        byte[] pdf = CredentialSlipDocument.render(slips, SlipsPerPage.TWO);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            for (int page = 1; page <= 2; page++) {
                assertThat(occurrences(pageText(document, page), CredentialSlipDocument.PROVENANCE_PREFIX))
                        .as("provenance count on page %d", page)
                        .isEqualTo(2);
            }
        }
    }

    /** §2.8's default: one per page, one page each, provenance still per slip. */
    @Test
    void oneSlipPerPageIsTheDefaultLayoutAndStillCarriesProvenance() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(
                List.of(slip("11111111", "AAAAAAAA1"), slip("22222222", "BBBBBBBB2")), SlipsPerPage.ONE);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(occurrences(pageText(document, 1), CredentialSlipDocument.PROVENANCE_PREFIX)).isEqualTo(1);
            assertThat(occurrences(pageText(document, 2), CredentialSlipDocument.PROVENANCE_PREFIX)).isEqualTo(1);
        }
    }

    /** §2.8: "issued by, at, valid before" — all three, on the printed line. */
    @Test
    void provenanceNamesTheIssuerTheIssueTimeAndTheExpiry() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "AAAAAAAA1")), SlipsPerPage.ONE);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = pageText(document, 1);
            assertThat(text).contains("Issued by Example Admin");
            assertThat(text).contains("2026-09-17 09:30 UTC");
            assertThat(text).contains("valid before");
            assertThat(text).contains("2026-09-24 09:30 UTC");
        }
    }

    /** §2.5's printed grouping, and the username the student types beside it. */
    @Test
    void slipPrintsTheUsernameAndTheGroupedCode() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "HKTZ49QP2M")), SlipsPerPage.ONE);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = pageText(document, 1);
            assertThat(text).contains("11111111");
            assertThat(text).contains("HKTZ4-9QP2M");
        }
    }

    /**
     * §2.9: the root URL is printed as text beside the QR because it is a
     * phishing anchor — something a child can check the address bar against —
     * and the deep link is deliberately <em>not</em> what gets printed for
     * typing.
     */
    @Test
    void slipPrintsTheSiteRootUrlAsTextAndNotTheDeepLink() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "HKTZ49QP2M")), SlipsPerPage.ONE);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = pageText(document, 1);
            assertThat(text).contains(ROOT);
            assertThat(text)
                    .as("the typed path is the root URL; the deep link belongs to the QR only")
                    .doesNotContain("/activate?code=");
            assertThat(text)
                    .as("the code must not appear in a printed URL a bystander can read off the page")
                    .doesNotContain(ROOT + "/activate");
        }
    }

    /**
     * §2.5: "one secret, two delivery forms". Decoded from a rasterised page,
     * not from the builder's input — so this fails if the QR is missing,
     * mispositioned off the page, drawn too small to resolve, or built from
     * anything other than the code printed beside it.
     */
    @Test
    void qrEncodesTheSameCodeWrappedInTheActivationUrl() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "HKTZ49QP2M")), SlipsPerPage.ONE);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> decoded = decodeQrCodes(new PDFRenderer(document).renderImageWithDPI(0, 200));
            assertThat(decoded).containsExactly(ROOT + "/activate?code=HKTZ49QP2M");
            assertThat(pageText(document, 1))
                    .as("the same code, printed for typing")
                    .contains("HKTZ4-9QP2M");
        }
    }

    /** Two slips on one sheet means two independently scannable codes. */
    @Test
    void eachSlipOnASharedPageCarriesItsOwnScannableQr() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(
                List.of(slip("11111111", "HKTZ49QP2M"), slip("22222222", "8XR3TN5W0")), SlipsPerPage.TWO);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> decoded = decodeQrCodes(new PDFRenderer(document).renderImageWithDPI(0, 200));
            assertThat(decoded).containsExactlyInAnyOrder(
                    ROOT + "/activate?code=HKTZ49QP2M",
                    ROOT + "/activate?code=8XR3TN5W0");
        }
    }

    /**
     * A CJK display name must not turn into {@code ???} or blow up the render.
     * Standard-14 Helvetica cannot encode it at all, which is why the builder
     * draws names rather than typing them.
     */
    @Test
    void aNonLatinDisplayNameRendersWithoutFailing() throws IOException {
        Slip slip = new Slip("同学示例", "11111111", "HKTZ49QP2M",
                ROOT + "/activate?code=HKTZ49QP2M", ROOT, "Example Admin", ISSUED, EXPIRES);

        byte[] pdf = CredentialSlipDocument.render(List.of(slip), SlipsPerPage.ONE);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(pageText(document, 1)).contains(CredentialSlipDocument.PROVENANCE_PREFIX);
        }
    }

    /** A slip is a live credential: {@code toString()} must never print one. */
    @Test
    void slipToStringRedactsTheCodeAndTheDeepLink() {
        assertThat(slip("11111111", "HKTZ49QP2M").toString())
                .doesNotContain("HKTZ49QP2M")
                .contains("<redacted>");
    }

    private static Slip slip(String username, String code) {
        return new Slip("Example Student " + username, username, code,
                ROOT + "/activate?code=" + code, ROOT, "Example Admin", ISSUED, EXPIRES);
    }

    private static String pageText(PDDocument document, int oneBasedPage) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(oneBasedPage);
        stripper.setEndPage(oneBasedPage);
        return stripper.getText(document);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * Decodes every QR on a rendered page. zxing's {@code javase} module would
     * supply {@code BufferedImageLuminanceSource}; the project depends only on
     * {@code core} (see {@code pom.xml}), so {@link GreyscaleSource} below is
     * the eight lines that replaces.
     */
    private static List<String> decodeQrCodes(BufferedImage page) {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new GreyscaleSource(page)));
        try {
            Result[] results = new GenericMultipleBarcodeReader(new MultiFormatReader()).decodeMultiple(bitmap,
                    Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE,
                            // Restricted to QR on purpose: left open, zxing also
                            // reads the printed 8-digit username as a 1D barcode
                            // and the assertion then fails on noise rather than
                            // on anything the slip got wrong.
                            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE)));
            return Arrays.stream(results).map(Result::getText).toList();
        } catch (NotFoundException e) {
            return List.of();
        }
    }

    private static final class GreyscaleSource extends LuminanceSource {

        private final byte[] luminance;
        private final int stride;
        private final int offsetX;
        private final int offsetY;

        private GreyscaleSource(BufferedImage image) {
            super(image.getWidth(), image.getHeight());
            this.stride = image.getWidth();
            this.offsetX = 0;
            this.offsetY = 0;
            this.luminance = new byte[image.getWidth() * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    int grey = ((rgb >> 16 & 0xFF) * 306 + (rgb >> 8 & 0xFF) * 601 + (rgb & 0xFF) * 117) >> 10;
                    luminance[y * stride + x] = (byte) grey;
                }
            }
        }

        private GreyscaleSource(byte[] luminance, int stride, int offsetX, int offsetY, int width, int height) {
            super(width, height);
            this.luminance = luminance;
            this.stride = stride;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            byte[] target = row != null && row.length >= getWidth() ? row : new byte[getWidth()];
            System.arraycopy(luminance, (offsetY + y) * stride + offsetX, target, 0, getWidth());
            return target;
        }

        @Override
        public byte[] getMatrix() {
            byte[] matrix = new byte[getWidth() * getHeight()];
            for (int y = 0; y < getHeight(); y++) {
                System.arraycopy(luminance, (offsetY + y) * stride + offsetX, matrix, y * getWidth(), getWidth());
            }
            return matrix;
        }

        /**
         * {@code GenericMultipleBarcodeReader} finds the second and subsequent
         * codes by cropping around the first and re-reading, so a source
         * without this throws rather than returning one result.
         */
        @Override
        public boolean isCropSupported() {
            return true;
        }

        @Override
        public LuminanceSource crop(int left, int top, int width, int height) {
            return new GreyscaleSource(luminance, stride, offsetX + left, offsetY + top, width, height);
        }
    }
}
