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
 * <h2>Everything is typed; only the QR is an image</h2>
 *
 * <p>Every word on the slip — labels, name, username, code, provenance — is
 * real PDF text. Names used to be the exception: they were rasterised through
 * AWT because Standard-14 Helvetica cannot encode CJK, and AWT could at least
 * find <em>some</em> font that could. That was wrong in a way no test on a
 * developer's machine could show. AWT's logical {@code SANS_SERIF} resolves
 * against system-installed fonts, and the deployment host has only DejaVu, so
 * the bitmap it produced for a Chinese name was a row of empty boxes on the
 * only machine that prints slips for real children. See {@link SlipFont} for
 * the full account and for why the fix is an embedded font rather than
 * installing one on the server.</p>
 *
 * <p>So the two-font split is now by <em>purpose</em>, not by what a typeface
 * can survive: {@link SlipFont} for all human-readable text, Standard-14
 * {@code Courier-Bold} for the username and the activation code, which are
 * ASCII by construction (§2.5's Crockford alphabet, §3.2's 8 digits) and want a
 * fixed pitch so {@code 0}/{@code O} and {@code 1}/{@code l} stay apart. The
 * bundled font has no bold to select — its variable axes are not applied by
 * PDFBox, so its default instance is Regular — so emphasis here is size and
 * colour, never a stroked fake bold.</p>
 */
final class CredentialSlipDocument {

    /** Matches {@code EvidenceWatermarker}'s convention: explicit zone, never a silent local one. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /** Fixed-pitch for the code: {@code 0}/{@code O} and {@code 1}/{@code l} stay distinguishable. */
    private static final PDFont CODE_FONT = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);

    private static final Color INK = Color.BLACK;
    private static final Color LABEL_INK = new Color(0x66, 0x66, 0x66);
    private static final Color BODY_INK = new Color(0x44, 0x44, 0x44);
    private static final Color CAPTION_INK = new Color(0x55, 0x55, 0x55);
    private static final Color PROVENANCE_INK = new Color(0x77, 0x77, 0x77);

    private static final float PAGE_MARGIN = 36f;
    private static final float SLIP_PADDING = 22f;
    private static final float NAME_POINTS = 16f;
    private static final float HEADING_POINTS = 12f;
    private static final float URL_POINTS = 12f;

    /**
     * The substitute for a character the bundled font has no glyph for. Noto
     * Sans SC covers the CJK a school roster actually contains, but not every
     * extension plane, and {@code showText} on a missing glyph throws — which
     * would fail a whole class's batch over one unusual name. A visible
     * {@code ?} in one name is recoverable by an admin; a 500 on the download
     * is not obviously anything.
     */
    private static final char UNPRINTABLE = '?';

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
     * The literal each language's provenance line starts with. Exposed to tests
     * so the "once per slip, not once per page" assertion counts the real
     * printed string rather than a copy that could drift away from it.
     */
    static String provenanceMarker(String resolvedLanguage) {
        return SlipCopy.forLanguage(resolvedLanguage).provenanceMarker();
    }

    /**
     * @param resolvedLanguage already resolved by the controller the way every
     *                         other endpoint resolves it ({@code Accept-Language}
     *                         → the principal's preference → the school
     *                         default). §2.9 wants the paper and the screen to
     *                         say the same words, so an admin working in Chinese
     *                         prints Chinese slips.
     * @return the finished PDF. One document for the whole batch (§2.8's
     *         rejected alternative was thirty attachments): one mail, one print
     *         job, and no student identifier in a filename.
     */
    static byte[] render(List<Slip> slips, SlipsPerPage slipsPerPage, String resolvedLanguage) {
        SlipCopy copy = SlipCopy.forLanguage(resolvedLanguage);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Once per document, not once per slip: a PDType0Font belongs to
            // exactly one PDDocument and accumulates that document's subset, so
            // loading it per page would embed the font program several times
            // over.
            PDFont body = SlipFont.embedInto(document);
            int perPage = slipsPerPage.value();
            for (int start = 0; start < slips.size(); start += perPage) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                List<Slip> onThisPage = slips.subList(start, Math.min(start + perPage, slips.size()));
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    drawPage(document, content, body, copy, page.getMediaBox(), onThisPage, perPage);
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
    private static void drawPage(PDDocument document, PDPageContentStream content, PDFont body, SlipCopy copy,
                                  PDRectangle box, List<Slip> slips, int perPage) throws IOException {
        float usableHeight = box.getHeight() - 2 * PAGE_MARGIN;
        float slipHeight = usableHeight / perPage;
        float slipWidth = box.getWidth() - 2 * PAGE_MARGIN;

        for (int i = 0; i < slips.size(); i++) {
            float top = box.getHeight() - PAGE_MARGIN - i * slipHeight;
            renderSlip(document, content, body, copy, slips.get(i),
                    PAGE_MARGIN, top - slipHeight, slipWidth, slipHeight);
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

    private static void renderSlip(PDDocument document, PDPageContentStream content, PDFont body, SlipCopy copy,
                                    Slip slip, float x, float y, float width, float height) throws IOException {
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
        text(content, body, 8f, CAPTION_INK, qrX, qrY - 12f, copy.scanCaption());

        // Everything in the left column has to clear the QR, and the Chinese
        // and English wordings are nowhere near the same length, so the column
        // width is measured rather than assumed.
        float column = qrX - left - 8f;

        float cursor = top - 12f;
        // Same words as the login page's first-time entry (§2.9: the screen and
        // the paper must use one vocabulary, or a child cannot match them).
        fittedText(content, body, HEADING_POINTS, 9f, column, INK, left, cursor, copy.heading());
        cursor -= 15f;
        fittedText(content, body, 9.5f, 7.5f, column, BODY_INK, left, cursor, copy.howToEnter());

        cursor -= 30f;
        text(content, body, 9f, LABEL_INK, left, cursor, copy.nameLabel());
        cursor -= NAME_POINTS + 2f;
        fittedText(content, body, NAME_POINTS, 10f, column, INK, left, cursor, printable(body, slip.displayName()));

        cursor -= 24f;
        text(content, body, 9f, LABEL_INK, left, cursor, copy.usernameLabel());
        cursor -= 16f;
        text(content, CODE_FONT, 14f, INK, left, cursor, slip.username());

        cursor -= 26f;
        text(content, body, 9f, LABEL_INK, left, cursor, copy.codeLabel());
        cursor -= 20f;
        text(content, CODE_FONT, 18f, INK, left, cursor, ActivationCodeFormat.group(slip.code()));

        cursor -= 26f;
        // §2.9's phishing anchor. Printed as its own line, labelled as the thing
        // to check, not buried in a sentence. It is emphasised against the grey
        // label above it by size and ink alone — see the class note on why there
        // is no bold to reach for.
        text(content, body, 9f, LABEL_INK, left, cursor, copy.checkAddress());
        cursor -= 15f;
        fittedText(content, body, URL_POINTS, 8f, column, INK, left, cursor,
                printable(body, slip.siteRootUrl()));

        // §2.8: on this slip, not on this page.
        fittedText(content, body, 7.5f, 5.5f, width - 2 * SLIP_PADDING, PROVENANCE_INK,
                left, y + SLIP_PADDING - 8f, provenance(copy, body, slip));
    }

    /**
     * {@code Issued by <admin> at <when> — valid before <when>}, or its Chinese
     * equivalent. Every part of §2.8's "issued by, at, valid before", on one
     * line, on every slip.
     *
     * <p>The issuing admin's real name is printed. It used to be folded to a
     * roster-neutral {@code "an administrator"} whenever it left Latin-1,
     * because the label font could not encode a CJK staff name — that
     * constraint is gone now the font is embedded, and folding a Chinese
     * administrator's name away while printing an English one would have made
     * the provenance line weaker in exactly the deployment it is written for.
     * The fallback survives for a genuinely absent name. Nothing on this line
     * identifies the student, and it never contains the code.</p>
     */
    private static String provenance(SlipCopy copy, PDFont font, Slip slip) {
        String issuer = slip.issuedBy() == null || slip.issuedBy().isBlank()
                ? copy.unnamedIssuer()
                : printable(font, slip.issuedBy());
        return copy.provenance(issuer, STAMP.format(slip.issuedAt()), STAMP.format(slip.expiresAt()));
    }

    private static void text(PDPageContentStream content, PDFont font, float size, Color color,
                              float x, float y, String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        content.beginText();
        content.setFont(font, size);
        content.setNonStrokingColor(color);
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }

    /**
     * Draws {@code value} at {@code preferred} points, shrinking toward
     * {@code minimum} until it fits {@code maxWidth}.
     *
     * <p>Not cosmetic. The same slip has to hold "First time logging in? Use
     * your activation code" and 首次登录？使用激活码 in the same column beside
     * the QR, and a name may be two characters or eight words. Without this,
     * one language's heading runs under the QR image and the layout only looks
     * right in whichever language someone happened to look at.</p>
     */
    private static void fittedText(PDPageContentStream content, PDFont font, float preferred, float minimum,
                                    float maxWidth, Color color, float x, float y, String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        float size = preferred;
        while (size > minimum && widthAt(font, value, size) > maxWidth) {
            size -= 0.25f;
        }
        text(content, font, size, color, x, y, value);
    }

    private static float widthAt(PDFont font, String value, float size) throws IOException {
        return font.getStringWidth(value) / 1000f * size;
    }

    /**
     * Replaces any code point the embedded font has no glyph for. PDFBox throws
     * from {@code showText} rather than substituting, so without this one rare
     * character in one student's name would fail the entire class's download —
     * and the failure would arrive as a 500 on a button, with nothing to say
     * which of thirty names caused it.
     *
     * <p>Applied only to the values that come from data (the student's name,
     * the issuing admin's name, the configured site URL). The fixed labels are
     * literals in {@link SlipCopy} and are covered by the font by inspection.</p>
     */
    private static String printable(PDFont font, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            i += Character.charCount(codePoint);
            try {
                font.getStringWidth(glyph);
                safe.append(glyph);
            } catch (IllegalArgumentException | IOException unmapped) {
                safe.append(UNPRINTABLE);
            }
        }
        return safe.toString();
    }
}
