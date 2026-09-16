package cn.timebank.file.application;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import cn.timebank.common.error.ApiException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Burns a per-viewer watermark into evidence <b>as it is delivered</b>
 * (08-SecurityPrivacyHardening.md §3.6/§3.7, finding W-8).
 *
 * <p><b>The stored file is never modified by this class.</b> Watermarking
 * happens on the way out, on a copy, every time. The {@code sha256Hash} on
 * {@code EvidenceFile} therefore describes the stored original and will
 * <em>not</em> match the bytes a browser received — that is correct and
 * intended, not drift to be repaired. Integrity verification is always
 * performed against the stored file. A future reader tempted to "fix" the
 * mismatch by hashing the delivered bytes, or by watermarking at upload
 * instead, would destroy both properties this design rests on: that the
 * evidence chain covers an unaltered original, and that the mark identifies
 * <em>who</em> viewed it.</p>
 *
 * <p>Applied to staff views only (decision D-1). A student looking at their
 * own photo gets it clean; the traceability exists for the path where someone
 * views material that is not theirs.</p>
 *
 * <p>A frontend overlay was rejected: the signed URL returns the real bytes,
 * so "open image in new tab" defeats it entirely.</p>
 */
@Component
public class EvidenceWatermarker {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /**
     * Fixed English, deliberately not localized. The mark is forensic: it
     * should read identically in a leaked screenshot regardless of the
     * viewer's UI language, so that comparing two copies is unambiguous.
     */
    private static final String PURPOSE = "Volunteer hours verification only";

    /** @return e.g. {@code "admin001 · 2026-09-16 13:04:02 UTC · Volunteer hours verification only"}. */
    public String describeViewer(String viewerAccount, Instant viewedAt) {
        return viewerAccount + "  ·  " + TIMESTAMP.format(viewedAt) + "  ·  " + PURPOSE;
    }

    /**
     * @param formatName {@code "png"} or {@code "jpg"} — must match what the file was stored as.
     */
    public byte[] watermarkImage(byte[] original, String formatName, String text) {
        try {
            BufferedImage source = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(original));
            if (source == null) {
                // Should be unreachable: complete() already decoded this image to
                // strip its metadata. Returning the original unmarked would be a
                // silent traceability hole, so fail loudly instead.
                throw new IOException("Stored evidence image could not be decoded for watermarking");
            }
            // JPEG has no alpha channel; writing an ARGB raster to it produces
            // colour-inverted output rather than an error.
            int type = "png".equals(formatName) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage marked = new BufferedImage(source.getWidth(), source.getHeight(), type);

            Graphics2D g = marked.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(source, 0, 0, null);
                drawTiledDiagonalText(g, source.getWidth(), source.getHeight(), text);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(marked, formatName, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to watermark evidence image", e);
        }
    }

    private void drawTiledDiagonalText(Graphics2D g, int width, int height, String text) {
        int fontSize = Math.max(12, Math.min(width, height) / 40);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
        g.setColor(Color.DARK_GRAY);
        // Low enough to read the evidence through, high enough that cropping the
        // mark out means cropping the evidence too.
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
        g.rotate(Math.toRadians(-30), width / 2.0, height / 2.0);

        int textWidth = g.getFontMetrics().stringWidth(text);
        int stepX = textWidth + fontSize * 4;
        int stepY = fontSize * 6;
        // Over-draw well past the edges so rotation leaves no bare corner.
        for (int y = -height; y < height * 2; y += stepY) {
            for (int x = -width; x < width * 2; x += stepX) {
                g.drawString(text, x, y);
            }
        }
    }

    /**
     * Content-stream overlay (decision D-2), not rasterization: the page's own
     * text stays selectable and searchable, which is a genuinely useful
     * property of a scan. A determined party with the right tooling can strip
     * an overlay, and that is accepted — the mark deters and traces, it does
     * not claim to be tamper-proof.
     */
    public byte[] watermarkPdf(byte[] original, String text) {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(original), "",
                null, null, IOUtils.createTempFileOnlyStreamCache())) {

            PDExtendedGraphicsState transparency = new PDExtendedGraphicsState();
            transparency.setNonStrokingAlphaConstant(0.18f);

            for (PDPage page : document.getPages()) {
                PDRectangle box = page.getMediaBox();
                try (PDPageContentStream content = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    content.setGraphicsStateParameters(transparency);
                    content.setNonStrokingColor(Color.DARK_GRAY);
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);

                    for (float y = -box.getHeight(); y < box.getHeight() * 2; y += 90) {
                        for (float x = -box.getWidth(); x < box.getWidth() * 2; x += 420) {
                            content.beginText();
                            content.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(30), x, y));
                            content.showText(sanitizeForWinAnsi(text));
                            content.endText();
                        }
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            // A stored file PDFBox cannot parse must not surface as an opaque
            // 500. Sanitization at upload rejects unparseable PDFs, so this can
            // only be a file stored before that existed — the caller deserves to
            // be told which file and why, not "an unexpected error occurred".
            throw ApiException.invalidRequest(
                    "This evidence file is not a readable PDF and cannot be displayed. "
                    + "It was stored before PDF validation was introduced and needs re-uploading.");
        }
    }

    /**
     * Standard-14 Helvetica is WinAnsi-encoded and {@code showText} throws on
     * anything outside it. Usernames are ASCII today, but a display name or a
     * future CJK account would otherwise turn a viewable document into a 500.
     */
    private String sanitizeForWinAnsi(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            sb.append(c >= 32 && c <= 255 ? c : '?');
        }
        return sb.toString();
    }
}
