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
 * different watermark" — with issue 123's amendment to what "the viewing
 * account" is allowed to look like: a person's name and email, not a login.
 */
class EvidenceWatermarkerTest {

    private final EvidenceWatermarker watermarker = new EvidenceWatermarker();

    private static final Instant VIEWED_AT = Instant.parse("2026-09-16T13:04:02Z");

    private static final String NAME = "Teacher Wei Chen";
    private static final String EMAIL = "wei.chen@example-township-school.edu";
    private static final String USERNAME = "admin001";

    /** The whole mark for one viewer, at the fixed instant above. */
    private String markFor(String displayName, String email, String username) {
        return watermarker.describeViewer(displayName, email, username, VIEWED_AT);
    }

    @Test
    void viewerTextCarriesTheNameEmailTimestampAndPurpose() {
        String text = markFor(NAME, EMAIL, USERNAME);

        assertThat(text).contains("Teacher Wei Chen <wei.chen@example-township-school.edu>");
        assertThat(text).contains("2026-09-16 13:04:02 UTC");
        assertThat(text).contains("Volunteer hours verification only");
    }

    /**
     * The point of the change, stated as a prohibition: the login handle is
     * meaningless to whoever reads a leaked copy, so it must not be what
     * identifies the viewer when a real identity is available.
     */
    @Test
    void theLoginIsNotWhatIdentifiesAViewerWhoHasAnEmail() {
        assertThat(markFor(NAME, EMAIL, USERNAME)).doesNotContain(USERNAME);
    }

    @Test
    void anAccountWithNoEmailFallsBackToTheUsername() {
        // Email is optional on every account (issue 106 §1), and a display
        // name alone is not unique — something resolvable has to remain.
        assertThat(markFor(NAME, null, USERNAME)).contains("Teacher Wei Chen (admin001)");
        assertThat(markFor(NAME, "   ", USERNAME)).contains("Teacher Wei Chen (admin001)");
    }

    /** Never `Name <>`: an empty pair of brackets identifies nobody. */
    @Test
    void theFallbackNeverRendersEmptyBrackets() {
        assertThat(markFor(NAME, null, USERNAME)).doesNotContain("<");
    }

    @Test
    void differentViewersGetDifferentText() {
        assertThat(markFor(NAME, EMAIL, USERNAME))
                .isNotEqualTo(markFor("Teacher Li Ming", "li.ming@example-township-school.edu", "teacher003"));
    }

    /**
     * Display names are not unique and nothing in the schema makes them so, so
     * two namesakes must still be told apart. The username is held constant
     * here precisely so it cannot be what does the telling — this fails on a
     * name-only mark, which is the mistake "use the name, not the login" most
     * easily becomes.
     */
    @Test
    void twoStaffSharingADisplayNameAreToldApartByTheEmailAlone() {
        assertThat(markFor(NAME, EMAIL, USERNAME))
                .isNotEqualTo(markFor(NAME, "w.chen2@example-township-school.edu", USERNAME));
    }

    @Test
    void theSameViewerAtADifferentTimeGetsDifferentText() {
        assertThat(watermarker.describeViewer(NAME, EMAIL, USERNAME, VIEWED_AT))
                .isNotEqualTo(watermarker.describeViewer(NAME, EMAIL, USERNAME, VIEWED_AT.plusSeconds(61)));
    }

    @Test
    void watermarkedImageStaysADecodableImageOfTheSameSize() throws Exception {
        byte[] original = pngOf(400, 300);

        byte[] marked = watermarker.watermarkImage(original, "png", markFor(NAME, EMAIL, USERNAME));

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(marked));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(400);
        assertThat(decoded.getHeight()).isEqualTo(300);
    }

    @Test
    void watermarkingAnImageDoesNotModifyTheInput() throws Exception {
        byte[] original = pngOf(120, 90);
        byte[] before = original.clone();

        watermarker.watermarkImage(original, "png", markFor(NAME, EMAIL, USERNAME));

        // The stored file must survive delivery untouched — the sha256 on the
        // EvidenceFile row still has to describe it afterwards.
        assertThat(original).isEqualTo(before);
    }

    @Test
    void twoViewersOfTheSameImageGetDifferentBytes() throws Exception {
        byte[] original = pngOf(300, 200);

        byte[] forAdmin = watermarker.watermarkImage(original, "png", markFor(NAME, EMAIL, USERNAME));
        byte[] forTeacher = watermarker.watermarkImage(original, "png",
                markFor("Teacher Li Ming", "li.ming@example-township-school.edu", "teacher003"));

        assertThat(forAdmin).isNotEqualTo(forTeacher);
    }

    @Test
    void jpegWatermarkingProducesAValidJpeg() throws Exception {
        byte[] original = jpegOf(200, 150);

        byte[] marked = watermarker.watermarkImage(original, "jpg", markFor(NAME, EMAIL, USERNAME));

        assertThat(ImageIO.read(new ByteArrayInputStream(marked))).isNotNull();
    }

    @Test
    void watermarkedPdfContainsTheViewersIdentityAsRealText() throws Exception {
        byte[] original = onePagePdf();

        byte[] marked = watermarker.watermarkPdf(original, markFor(NAME, EMAIL, USERNAME));

        try (PDDocument document = Loader.loadPDF(marked)) {
            // Extractable text, not a flattened raster — that is the whole
            // reason D-2 chose an overlay over rasterizing.
            String rendered = compact(new PDFTextStripper().getText(document));
            assertThat(rendered).contains("TeacherWeiChen<wei.chen@example-township-school.edu>");
            assertThat(rendered).contains("Volunteerhoursverificationonly");
            assertThat(rendered).doesNotContain(USERNAME);
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void twoViewersOfTheSamePdfGetDifferentDocuments() throws Exception {
        byte[] original = onePagePdf();

        byte[] forAdmin = watermarker.watermarkPdf(original, markFor(NAME, EMAIL, USERNAME));
        byte[] forTeacher = watermarker.watermarkPdf(original,
                markFor("Teacher Li Ming", "li.ming@example-township-school.edu", "teacher003"));

        try (PDDocument a = Loader.loadPDF(forAdmin); PDDocument b = Loader.loadPDF(forTeacher)) {
            assertThat(compact(new PDFTextStripper().getText(a)))
                    .contains("TeacherWeiChen<wei.chen@example-township-school.edu>")
                    .doesNotContain("li.ming@example-township-school.edu");
            assertThat(compact(new PDFTextStripper().getText(b)))
                    .contains("TeacherLiMing<li.ming@example-township-school.edu>")
                    .doesNotContain("wei.chen@example-township-school.edu");
        }
    }

    @Test
    void watermarkingAPdfDoesNotModifyTheInput() throws Exception {
        byte[] original = onePagePdf();
        byte[] before = original.clone();

        watermarker.watermarkPdf(original, markFor(NAME, EMAIL, USERNAME));

        assertThat(original).isEqualTo(before);
    }

    /**
     * Now the common case rather than the exotic one: real display names in
     * this school are Chinese (issue 123 §3). Standard-14 Helvetica is
     * WinAnsi-encoded and an unmapped glyph throws rather than degrading,
     * which would turn a viewable document into a 500 — so the name is
     * replaced glyph-by-glyph, and the identity has to survive in what is
     * left.
     */
    @Test
    void aChineseNameDegradesInAPdfButTheIdentifyingHalfSurvives() throws Exception {
        byte[] marked = watermarker.watermarkPdf(onePagePdf(), markFor("李老师", EMAIL, USERNAME));

        try (PDDocument document = Loader.loadPDF(marked)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            String rendered = compact(new PDFTextStripper().getText(document));
            assertThat(rendered).contains("???<wei.chen@example-township-school.edu>");
            assertThat(rendered).contains("Volunteerhoursverificationonly");
        }
    }

    /** The same degradation with no email to fall back on still names an account. */
    @Test
    void aChineseNameWithNoEmailStillLeavesTheUsernameReadableInAPdf() throws Exception {
        byte[] marked = watermarker.watermarkPdf(onePagePdf(), markFor("李老师", null, USERNAME));

        try (PDDocument document = Loader.loadPDF(marked)) {
            assertThat(compact(new PDFTextStripper().getText(document))).contains("???(admin001)");
        }
    }

    @Test
    void theComposedTextItselfKeepsAChineseNameIntact() throws Exception {
        // The `?` substitution is the PDF writer's, not the composer's: the
        // text handed to the image path — most of the evidence — is unmangled,
        // and Graphics2D has no WinAnsi limit.
        assertThat(markFor("李老师", EMAIL, USERNAME))
                .startsWith("李老师 <wei.chen@example-township-school.edu>");
        assertThat(ImageIO.read(new ByteArrayInputStream(
                watermarker.watermarkImage(pngOf(400, 300), "png", markFor("李老师", EMAIL, USERNAME))))).isNotNull();
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

    // ── The PDF mark, which cannot draw a CJK name at all (issue 123) ───────

    @Test
    void aPdfMarkDropsAnUndrawableNameInFavourOfTheEmailRatherThanPrintingQuestionMarks() {
        String text = watermarker.describeViewerForPdf(
                "李老师", "wei.chen@example-township-school.edu", "teacher001", VIEWED_AT);

        assertThat(text).doesNotContain("?");
        assertThat(text).doesNotContain("李老师");
        assertThat(text).startsWith("wei.chen@example-township-school.edu  ·  ");
        assertThat(text).contains("Volunteer hours verification only");
    }

    @Test
    void aPdfMarkKeepsAnAsciiNameBecauseItCanActuallyBeDrawn() {
        String text = watermarker.describeViewerForPdf(
                "Teacher Wei Chen", "wei.chen@example-township-school.edu", "teacher001", VIEWED_AT);

        assertThat(text).startsWith("Teacher Wei Chen <wei.chen@example-township-school.edu>  ·  ");
    }

    @Test
    void aPdfMarkForAnUndrawableNameWithNoEmailFallsBackToTheUsername() {
        String text = watermarker.describeViewerForPdf("李老师", null, "teacher001", VIEWED_AT);

        assertThat(text).doesNotContain("?");
        assertThat(text).startsWith("teacher001  ·  ");
    }

    /** Whatever it falls back to must still be drawable, or the fallback achieved nothing. */
    @Test
    void everyPdfMarkSurvivesTheWinAnsiSanitizerUnchanged() {
        for (String[] c : new String[][] {
                { "李老师", "wei.chen@example-township-school.edu", "teacher001" },
                { "李老师", null, "teacher001" },
                { "Teacher Wei Chen", "wei.chen@example-township-school.edu", "teacher001" },
                { "Teacher Wei Chen", null, "teacher001" },
        }) {
            String text = watermarker.describeViewerForPdf(c[0], c[1], c[2], VIEWED_AT);
            for (char ch : text.toCharArray()) {
                assertThat(ch >= 32 && ch <= 255)
                        .as("drawable char in %s", text)
                        .isTrue();
            }
        }
    }
}
