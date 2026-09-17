package cn.timebank.file.application;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §3.7's verification: "a PDF carrying JavaScript must lose it on upload; an
 * encrypted PDF must be rejected".
 *
 * <p>These are unit tests — the sanitizer is a pure byte-to-byte function with
 * no database or Spring context — so they run under {@code mvn test} rather
 * than needing a MySQL schema.</p>
 */
class PdfSanitizerTest {

    private static final String JS_SOURCE = "app.alert('this should not survive');";

    private final PdfSanitizer sanitizer = new PdfSanitizer(100);

    @Test
    void stripsDocumentLevelJavaScript() throws Exception {
        byte[] hostile = pdfWithJavaScript();
        // Guard the fixture itself: a test that silently built a clean PDF would
        // pass while proving nothing. Asserted against the parsed object graph,
        // not the raw bytes — PDFBox deflates object streams, so the JavaScript
        // is really in there but is not greppable as plaintext. (Scanning the
        // bytes is what this assertion did first, and it failed for exactly
        // that reason.)
        assertThat(javaScriptNamesIn(hostile)).isNotNull();

        byte[] clean = sanitizer.sanitize(hostile);

        assertThat(javaScriptNamesIn(clean)).isNull();
    }

    /** @return the catalog's {@code /Names /JavaScript} entry, or null once stripped. */
    private COSDictionary javaScriptNamesIn(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            COSDictionary names = document.getDocumentCatalog().getCOSObject()
                    .getCOSDictionary(COSName.NAMES);
            return names == null ? null : names.getCOSDictionary(COSName.getPDFName("JavaScript"));
        }
    }

    @Test
    void stripsTheOpenActionThatFiresWithoutUserInteraction() throws Exception {
        byte[] clean = sanitizer.sanitize(pdfWithJavaScript());
        try (PDDocument reloaded = Loader.loadPDF(clean)) {
            assertThat(reloaded.getDocumentCatalog().getOpenAction()).isNull();
        }
    }

    @Test
    void stripsEmbeddedFiles() throws Exception {
        byte[] clean = sanitizer.sanitize(pdfWithJavaScript());
        try (PDDocument reloaded = Loader.loadPDF(clean)) {
            COSDictionary names = reloaded.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.NAMES);
            assertThat(names == null ? null : names.getCOSDictionary(COSName.getPDFName("EmbeddedFiles"))).isNull();
        }
    }

    @Test
    void keepsThePageContentItWasGiven() throws Exception {
        byte[] clean = sanitizer.sanitize(pdfWithJavaScript());
        try (PDDocument reloaded = Loader.loadPDF(clean)) {
            // Sanitizing removes behaviour, not the scan itself — the whole point
            // of keeping PDF support rather than dropping the format.
            assertThat(reloaded.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void rejectsAPasswordProtectedPdf() throws Exception {
        byte[] encrypted = encryptedPdf();
        assertThatThrownBy(() -> sanitizer.sanitize(encrypted))
                .isInstanceOf(PdfSanitizer.UnacceptablePdfException.class)
                .hasMessageContaining("PDF");
    }

    @Test
    void rejectsAStructurallyCorruptPdf() {
        // Right magic bytes, nothing behind them — the shape a truncated or
        // hand-crafted upload takes.
        byte[] corrupt = "%PDF-1.7\nthis is not a document".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> sanitizer.sanitize(corrupt))
                .isInstanceOf(PdfSanitizer.UnacceptablePdfException.class);
    }

    @Test
    void rejectsAPdfOverThePageCap() throws Exception {
        PdfSanitizer tinyCap = new PdfSanitizer(2);
        assertThatThrownBy(() -> tinyCap.sanitize(pdfWithPages(3)))
                .isInstanceOf(PdfSanitizer.UnacceptablePdfException.class)
                .hasMessageContaining("limit is 2");
    }

    @Test
    void acceptsAPdfAtExactlyThePageCap() throws Exception {
        PdfSanitizer tinyCap = new PdfSanitizer(2);
        assertThat(tinyCap.sanitize(pdfWithPages(2))).isNotEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /** A one-page PDF carrying document-level JavaScript, a JS open action, and an embedded file. */
    private byte[] pdfWithJavaScript() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDDocumentCatalog catalog = document.getDocumentCatalog();

            COSDictionary jsAction = new COSDictionary();
            jsAction.setItem(COSName.S, COSName.getPDFName("JavaScript"));
            jsAction.setString(COSName.getPDFName("JS"), JS_SOURCE);

            COSArray namePairs = new COSArray();
            namePairs.add(new COSString("EvilScript"));
            namePairs.add(jsAction);
            COSDictionary jsTree = new COSDictionary();
            jsTree.setItem(COSName.NAMES, namePairs);

            COSArray embeddedPairs = new COSArray();
            embeddedPairs.add(new COSString("payload.txt"));
            embeddedPairs.add(new COSDictionary());
            COSDictionary embeddedTree = new COSDictionary();
            embeddedTree.setItem(COSName.NAMES, embeddedPairs);

            COSDictionary names = new COSDictionary();
            names.setItem(COSName.getPDFName("JavaScript"), jsTree);
            names.setItem(COSName.getPDFName("EmbeddedFiles"), embeddedTree);
            catalog.getCOSObject().setItem(COSName.NAMES, names);

            catalog.getCOSObject().setItem(COSName.OPEN_ACTION, jsAction);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] encryptedPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("Owner-Secret-77", "User-Secret-77", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] pdfWithPages(int count) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < count; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
