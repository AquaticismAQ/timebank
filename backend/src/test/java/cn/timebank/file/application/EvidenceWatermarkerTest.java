package cn.timebank.file.application;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.6/§3.7's verification: "a viewed PDF must show the viewing account and
 * time, and a second account viewing the same file must get a visibly
 * different watermark".
 */
class EvidenceWatermarkerTest {

    private final EvidenceWatermarker watermarker = new EvidenceWatermarker();

    private static final Instant VIEWED_AT = Instant.parse("2026-09-16T13:04:02Z");

    @Test
    void viewerTextCarriesAccountTimestampAndPurpose() {
        String text = watermarker.describeViewer("admin001", VIEWED_AT);

        assertThat(text).contains("admin001");
        assertThat(text).contains("2026-09-16 13:04:02 UTC");
        assertThat(text).contains("Volunteer hours verification only");
    }

    @Test
    void differentViewersGetDifferentText() {
        assertThat(watermarker.describeViewer("admin001", VIEWED_AT))
                .isNotEqualTo(watermarker.describeViewer("teacher003", VIEWED_AT));
    }

    @Test
    void theSameViewerAtADifferentTimeGetsDifferentText() {
        assertThat(watermarker.describeViewer("admin001", VIEWED_AT))
                .isNotEqualTo(watermarker.describeViewer("admin001", VIEWED_AT.plusSeconds(61)));
    }

    @Test
    void watermarkedImageStaysADecodableImageOfTheSameSize() throws Exception {
        byte[] original = pngOf(400, 300);

        byte[] marked = watermarker.watermarkImage(original, "png",
                watermarker.describeViewer("admin001", VIEWED_AT));

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(marked));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(400);
        assertThat(decoded.getHeight()).isEqualTo(300);
    }

    @Test
    void watermarkingAnImageDoesNotModifyTheInput() throws Exception {
        byte[] original = pngOf(120, 90);
        byte[] before = original.clone();

        watermarker.watermarkImage(original, "png", watermarker.describeViewer("admin001", VIEWED_AT));

        // The stored file must survive delivery untouched — the sha256 on the
        // EvidenceFile row still has to describe it afterwards.
        assertThat(original).isEqualTo(before);
    }

    @Test
    void twoViewersOfTheSameImageGetDifferentBytes() throws Exception {
        byte[] original = pngOf(300, 200);

        byte[] forAdmin = watermarker.watermarkImage(original, "png",
                watermarker.describeViewer("admin001", VIEWED_AT));
        byte[] forTeacher = watermarker.watermarkImage(original, "png",
                watermarker.describeViewer("teacher003", VIEWED_AT));

        assertThat(forAdmin).isNotEqualTo(forTeacher);
    }

    @Test
    void jpegWatermarkingProducesAValidJpeg() throws Exception {
        byte[] original = jpegOf(200, 150);

        byte[] marked = watermarker.watermarkImage(original, "jpg",
                watermarker.describeViewer("admin001", VIEWED_AT));

        assertThat(ImageIO.read(new ByteArrayInputStream(marked))).isNotNull();
    }

    @Test
    void watermarkedPdfContainsTheViewersAccountAsRealText() throws Exception {
        byte[] original = onePagePdf();

        byte[] marked = watermarker.watermarkPdf(original,
                watermarker.describeViewer("admin001", VIEWED_AT));

        try (PDDocument document = Loader.loadPDF(marked)) {
            // Extractable text, not a flattened raster — that is the whole
            // reason D-2 chose an overlay over rasterizing.
            String rendered = compact(new PDFTextStripper().getText(document));
            assertThat(rendered).contains("admin001");
            assertThat(rendered).contains("Volunteerhoursverificationonly");
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void twoViewersOfTheSamePdfGetDifferentDocuments() throws Exception {
        byte[] original = onePagePdf();

        byte[] forAdmin = watermarker.watermarkPdf(original, watermarker.describeViewer("admin001", VIEWED_AT));
        byte[] forTeacher = watermarker.watermarkPdf(original, watermarker.describeViewer("teacher003", VIEWED_AT));

        try (PDDocument a = Loader.loadPDF(forAdmin); PDDocument b = Loader.loadPDF(forTeacher)) {
            assertThat(compact(new PDFTextStripper().getText(a))).contains("admin001").doesNotContain("teacher003");
            assertThat(compact(new PDFTextStripper().getText(b))).contains("teacher003").doesNotContain("admin001");
        }
    }

    @Test
    void watermarkingAPdfDoesNotModifyTheInput() throws Exception {
        byte[] original = onePagePdf();
        byte[] before = original.clone();

        watermarker.watermarkPdf(original, watermarker.describeViewer("admin001", VIEWED_AT));

        assertThat(original).isEqualTo(before);
    }

    @Test
    void nonLatinAccountNamesDoNotBreakPdfDelivery() throws Exception {
        // Standard-14 Helvetica is WinAnsi-encoded; an unmapped glyph throws
        // rather than degrading, which would turn a viewable document into a 500.
        byte[] marked = watermarker.watermarkPdf(onePagePdf(),
                watermarker.describeViewer("李老师", VIEWED_AT));

        assertThat(Loader.loadPDF(marked).getNumberOfPages()).isEqualTo(1);
    }

    /**
     * PDFBox's text extractor inserts line breaks wherever the tiled overlay
     * wraps, so the mark comes back as "Volunteer h\nours verific\nation only".
     * The text is intact; only its whitespace is an artefact of extraction, so
     * assertions compare the whitespace-free form.
     */
    private String compact(String extracted) {
        return extracted.replaceAll("\\s+", "");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private byte[] pngOf(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private byte[] jpegOf(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private byte[] onePagePdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
