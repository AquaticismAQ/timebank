package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.CredentialSlipDtos.SlipsPerPage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The printable credential slip (10-StudentIdentityAndOnboarding.md §2.8/§3.4).
 *
 * <p><b>Streamed, never stored.</b> This class returns bytes and keeps nothing:
 * no temp file, no {@code FileStorage} record, no cache. It deliberately does
 * not go through the evidence file pipeline, which sanitizes and watermarks
 * <em>evidence</em> — a document that exists to carry a working credential to
 * one named child is not evidence, and putting it in a store that hands out
 * short-lived URLs would give the credential a second, longer life than the
 * download it was requested for.</p>
 *
 * <h2>Why the provenance line repeats on every slip</h2>
 *
 * <p>§2.8 is explicit: the sheet gets cut. Two slips per page means someone
 * takes scissors to it, and a mark printed once per page survives on exactly
 * one of the two pieces. So {@link Slip} carries its own issuer, issue time and
 * expiry, and {@link #renderSlip} draws them inside each slip's own box — the
 * repetition is structural rather than a rule someone has to remember.</p>
 *
 * <h2>The printed root URL</h2>
 *
 * <p>§2.9: the QR carries a deep link so scanning is one tap, and the site's
 * <em>root</em> URL is printed as text beside it. That text is a phishing
 * anchor, not a convenience — this design teaches children to scan a code and
 * then type a credential into whatever page appears, so the slip has to give
 * them something to check the address bar against. It is printed whether or not
 * anyone ever types it.</p>
 *
 * <h2>Names are drawn, labels are typed</h2>
 *
 * <p>Every fixed label, the username, the code and the provenance line are real
 * PDF text in Standard-14 fonts, so the document needs no embedded typeface and
 * prints identically anywhere. The two human names are not: Standard-14 is
 * WinAnsi-encoded and {@code showText} throws on anything outside it, and a
 * roster of CJK names is the normal case here, not an edge case. Substituting
 * {@code ?} the way the evidence watermarker does is fine for a watermark and
 * useless here — a head teacher handing out thirty slips needs to read the
 * name. So names go through {@link #textImage}, which renders them with
 * whatever system font AWT resolves, and only names do: keeping the labels as
 * text is what lets the provenance line be asserted against extracted text
 * rather than against this builder's own intermediate state.</p>
 */
final class CredentialSlipDocument {

    /** Matches {@code EvidenceWatermarker}'s convention: explicit zone, never a silent local one. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final PDFont LABEL = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont LABEL_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    /** Fixed-pitch for the code: {@code 0}/{@code O} and {@code 1}/{@code l} stay distinguishable. */
    private static final PDFont CODE_FONT = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);

    private static final float PAGE_MARGIN = 36f;
    private static final float SLIP_PADDING = 22f;
    private static final float NAME_POINTS = 16f;

    /**
     * The literal the provenance line starts with. Exposed to tests so the
     * "once per slip, not once per page" assertion counts the real printed
     * string rather than a copy that could drift away from it.
     */
    static final String PROVENANCE_PREFIX = "Issued by ";

    private CredentialSlipDocument() {
    }

    /**
     * One slip's worth of print. Carries its own provenance precisely because
     * the sheet may be cut — see the class note.
     *
     * <p>{@code activationUrl} embeds the plaintext activation code.
     * {@code toString()} is overridden for the same reason
     * {@code ActivationService.IssuedCode}'s is: a record's generated one would
     * print a live credential into the first log line that ever takes one.</p>
     */
    record Slip(String displayName, String username, String code, String activationUrl,
                String siteRootUrl, String issuedBy, Instant issuedAt, Instant expiresAt) {

        @Override
        public String toString() {
            return "Slip[username=" + username + ", code=<redacted>, activationUrl=<redacted>"
                    + ", issuedBy=" + issuedBy + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * @return the finished PDF. One document for the whole batch (§2.8's
     *         rejected alternative was thirty attachments): one mail, one print
     *         job, and no student identifier in a filename.
     */
    static byte[] render(List<Slip> slips, SlipsPerPage slipsPerPage) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int perPage = slipsPerPage.value();
            for (int start = 0; start < slips.size(); start += perPage) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                List<Slip> onThisPage = slips.subList(start, Math.min(start + perPage, slips.size()));
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    drawPage(document, content, page.getMediaBox(), onThisPage, perPage);
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render the credential slip document", e);
        }
    }

    /**
     * Slip boxes are laid out against {@code perPage}, not against how many
     * slips this page actually got, so a final half-empty page keeps the same
     * box geometry as every page before it — the cut line stays in the same
     * place across the whole sheet, which is the only reason anyone can cut a
     * stack of them at once.
     */
    private static void drawPage(PDDocument document, PDPageContentStream content, PDRectangle box,
                                  List<Slip> slips, int perPage) throws IOException {
        float usableHeight = box.getHeight() - 2 * PAGE_MARGIN;
        float slipHeight = usableHeight / perPage;
        float slipWidth = box.getWidth() - 2 * PAGE_MARGIN;

        for (int i = 0; i < slips.size(); i++) {
            float top = box.getHeight() - PAGE_MARGIN - i * slipHeight;
            renderSlip(document, content, slips.get(i), PAGE_MARGIN, top - slipHeight, slipWidth, slipHeight);
        }
        if (perPage > 1) {
            drawCutGuides(content, box, slipHeight, perPage);
        }
    }

    /** A dashed rule where the scissors go, one per internal boundary. */
    private static void drawCutGuides(PDPageContentStream content, PDRectangle box, float slipHeight, int perPage)
            throws IOException {
        content.setStrokingColor(new Color(0x99, 0x99, 0x99));
        content.setLineDashPattern(new float[]{3f, 3f}, 0f);
        content.setLineWidth(0.5f);
        for (int i = 1; i < perPage; i++) {
            float y = box.getHeight() - PAGE_MARGIN - i * slipHeight;
            content.moveTo(PAGE_MARGIN / 2, y);
            content.lineTo(box.getWidth() - PAGE_MARGIN / 2, y);
        }
        content.stroke();
        content.setLineDashPattern(new float[]{}, 0f);
    }

    private static void renderSlip(PDDocument document, PDPageContentStream content, Slip slip,
                                    float x, float y, float width, float height) throws IOException {
        content.setStrokingColor(new Color(0xBB, 0xBB, 0xBB));
        content.setLineWidth(0.75f);
        content.addRect(x, y, width, height);
        content.stroke();

        float left = x + SLIP_PADDING;
        float top = y + height - SLIP_PADDING;

        // The QR sits top-right so the left column can grow downward without
        // reflowing it. Sized from the slip box rather than fixed, so the
        // two-per-page layout shrinks it evenly instead of clipping it.
        float qrSize = Math.min(height - 2 * SLIP_PADDING - 34f, 150f);
        float qrX = x + width - SLIP_PADDING - qrSize;
        float qrY = top - qrSize;
        PDImageXObject qr = LosslessFactory.createFromImage(document, QrCodeImage.of(slip.activationUrl()));
        content.drawImage(qr, qrX, qrY, qrSize, qrSize);
        text(content, LABEL, 8f, new Color(0x55, 0x55, 0x55), qrX, qrY - 12f, "Scan to activate");

        float cursor = top - 12f;
        // Same words as the login page's first-time entry (§2.9: the screen and
        // the paper must use one vocabulary, or a child cannot match them).
        text(content, LABEL_BOLD, 13f, Color.BLACK, left, cursor, "First time logging in?");
        cursor -= 15f;
        text(content, LABEL, 9.5f, new Color(0x44, 0x44, 0x44), left, cursor,
                "Scan the code, or go to the address below.");

        cursor -= 30f;
        text(content, LABEL, 9f, new Color(0x66, 0x66, 0x66), left, cursor, "Name");
        cursor -= NAME_POINTS + 2f;
        drawName(document, content, slip.displayName(), left, cursor);

        cursor -= 24f;
        text(content, LABEL, 9f, new Color(0x66, 0x66, 0x66), left, cursor, "Username");
        cursor -= 16f;
        text(content, CODE_FONT, 14f, Color.BLACK, left, cursor, slip.username());

        cursor -= 26f;
        text(content, LABEL, 9f, new Color(0x66, 0x66, 0x66), left, cursor, "Activation code");
        cursor -= 20f;
        text(content, CODE_FONT, 18f, Color.BLACK, left, cursor, ActivationCodeFormat.group(slip.code()));

        cursor -= 26f;
        // §2.9's phishing anchor. Printed as its own line, labelled as the thing
        // to check, not buried in a sentence.
        text(content, LABEL, 9f, new Color(0x66, 0x66, 0x66), left, cursor,
                "Check the address bar says exactly");
        cursor -= 14f;
        text(content, LABEL_BOLD, 11f, Color.BLACK, left, cursor, slip.siteRootUrl());

        // §2.8: on this slip, not on this page.
        text(content, LABEL, 7.5f, new Color(0x77, 0x77, 0x77), left, y + SLIP_PADDING - 8f, provenance(slip));
    }

    /**
     * {@code Issued by <admin> at <when> — valid before <when>}. Every part of
     * §2.8's "issued by, at, valid before", on one line, on every slip.
     *
     * <p>The issuing admin's name is drawn with the label font, so a CJK staff
     * name would be unprintable here — it is folded to the roster-neutral
     * {@code "an administrator"} rather than substituted character by character
     * into something that looks like a name and is not. Nothing on this line
     * identifies the student, and it never contains the code.</p>
     */
    private static String provenance(Slip slip) {
        return PROVENANCE_PREFIX + winAnsiOr(slip.issuedBy(), "an administrator")
                + " at " + STAMP.format(slip.issuedAt())
                + "  —  valid before " + STAMP.format(slip.expiresAt());
    }

    private static String winAnsiOr(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        for (char c : value.toCharArray()) {
            if (c < 32 || c > 255) {
                return fallback;
            }
        }
        return value;
    }

    private static void text(PDPageContentStream content, PDFont font, float size, Color color,
                              float x, float y, String value) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.setNonStrokingColor(color);
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }

    private static void drawName(PDDocument document, PDPageContentStream content, String displayName,
                                  float x, float baseline) throws IOException {
        if (displayName == null || displayName.isBlank()) {
            return;
        }
        Rendered rendered = textImage(displayName, NAME_POINTS);
        PDImageXObject image = LosslessFactory.createFromImage(document, rendered.image());
        content.drawImage(image, x, baseline - rendered.descent(), rendered.width(), rendered.height());
    }

    /**
     * A string rendered through AWT at {@code OVERSAMPLE}x and reported back in
     * PDF points. Oversampling is what keeps a name crisp when a printer
     * rasterises it — embedding at exactly the final point size would hand the
     * printer a bitmap at ~72dpi.
     */
    private static Rendered textImage(String value, float pointSize) {
        final int oversample = 6;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(pointSize * oversample));

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D probeGraphics = probe.createGraphics();
        probeGraphics.setFont(font);
        FontMetrics metrics = probeGraphics.getFontMetrics();
        int pixelWidth = Math.max(1, metrics.stringWidth(value));
        int pixelHeight = Math.max(1, metrics.getHeight());
        int ascent = metrics.getAscent();
        int descent = metrics.getDescent();
        probeGraphics.dispose();

        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(font);
            graphics.setColor(Color.BLACK);
            graphics.drawString(value, 0, ascent);
        } finally {
            graphics.dispose();
        }
        return new Rendered(image, pixelWidth / (float) oversample, pixelHeight / (float) oversample,
                descent / (float) oversample);
    }

    private record Rendered(BufferedImage image, float width, float height, float descent) {
    }
}
