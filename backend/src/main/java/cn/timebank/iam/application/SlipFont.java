package cn.timebank.iam.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The one typeface a credential slip is allowed to depend on.
 *
 * <h2>Why the slip carries its own font at all</h2>
 *
 * <p>Both of the obvious alternatives are broken, and one of them was broken
 * silently in production for a while:</p>
 *
 * <ul>
 *   <li><b>PDFBox's Standard-14 fonts</b> (Helvetica, Courier) are
 *       WinAnsi-encoded. {@code showText} throws on anything outside Latin-1,
 *       so a Chinese label or a Chinese name cannot be typed with them at
 *       all — not degraded, not substituted, thrown.</li>
 *   <li><b>Drawing text through AWT</b> ({@code new Font(Font.SANS_SERIF, …)}
 *       into a {@code BufferedImage}, then placing the bitmap) resolves the
 *       logical {@code SANS_SERIF} family against <em>system-installed</em>
 *       fonts. That passes on a developer's macOS, which ships Hiragino Sans
 *       GB, and fails on the deployment host, which has only DejaVu:
 *       {@code new Font(Font.SANS_SERIF, Font.BOLD, 24).canDisplay('李')}
 *       returns {@code false} there, so every Chinese name printed as empty
 *       boxes on the one machine that matters. A unit test on a dev laptop
 *       structurally cannot catch that.</li>
 * </ul>
 *
 * <p>So the font ships in the jar and the PDF embeds a subset of it. The
 * rendering is then a pure function of bytes we control, on any host, with no
 * fontconfig, no headless-AWT surprise, and — because the glyphs are real
 * text rather than a bitmap — a name that can be selected, searched and copied
 * out of the finished document.</p>
 *
 * <h2>Why this file and not a smaller one</h2>
 *
 * <p>{@code NotoSansSC.ttf} is the glyf-based variable TTF from
 * {@code google/fonts} (SIL OFL 1.1, licence beside it). The ~8 MB CFF/OTF
 * build from the noto-cjk release is tempting and unusable:
 * {@code PDType0Font.load} on it throws
 * {@code UnsupportedOperationException: OTF fonts do not have a glyf table}.
 * Do not swap it back.</p>
 *
 * <p>Its default instance is <b>Regular</b>. A variable font's other weights
 * live in {@code gvar} deltas that PDFBox does not apply, so there is no bold
 * to select here — emphasis on the slip is done with size and colour, and
 * deliberately not by stroking a fake bold.</p>
 *
 * <h2>What is cached, and at which level</h2>
 *
 * <p>The <b>bytes</b> are cached, once, for the life of the JVM; the
 * {@link PDType0Font} is not, because it cannot be. A {@code PDType0Font}
 * owns the subsetting state for one specific {@code PDDocument} and writes
 * itself into that document's object graph, so sharing one across documents
 * corrupts both. The parsed {@code TrueTypeFont} underneath could in principle
 * be shared instead, and is not: its backing data stream carries a mutable
 * read position, and glyph outlines are pulled from it during
 * {@code document.save()}, so two slip batches rendering at once would seek
 * each other's reads. Re-parsing per document costs a few tens of
 * milliseconds; re-reading 17 MB off the classpath per document would cost
 * more, which is the part worth caching on a 1 vCPU host.</p>
 */
final class SlipFont {

    /** Classpath location, relative to {@code src/main/resources}. */
    static final String RESOURCE = "fonts/NotoSansSC.ttf";

    private SlipFont() {
    }

    /**
     * Holder idiom: the 17 MB read happens on first use and exactly once, with
     * the class initialiser doing the locking, and never at all in a JVM that
     * renders no slips.
     */
    private static final class Program {

        private static final byte[] BYTES = read();

        private static byte[] read() {
            try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("Bundled slip font " + RESOURCE + " is missing from the classpath", e);
            }
        }
    }

    /**
     * @param embedSubset always {@code true} at the call site, and the reason
     *                    is worth stating: with it {@code false} PDFBox embeds
     *                    the whole 17 MB font program into <em>every</em> slip
     *                    batch, turning a 30 kB download into a 17 MB one
     *                    without failing any functional assertion. A size
     *                    assertion in {@code CredentialSlipDocumentTest} is the
     *                    guard against that flip.
     */
    static PDType0Font embedInto(PDDocument document) throws IOException {
        return PDType0Font.load(document, new ByteArrayInputStream(Program.BYTES), true);
    }

    /** Exposed so a test can compare a rendered PDF against the source font's real size. */
    static int programByteCount() {
        return Program.BYTES.length;
    }
}
