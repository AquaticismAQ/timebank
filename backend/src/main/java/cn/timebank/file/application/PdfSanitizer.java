package cn.timebank.file.application;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Strips active content from an uploaded PDF (08-SecurityPrivacyHardening.md
 * §3.7, finding W-9).
 *
 * <p>A PDF is not a picture. Unlike the JPEG/PNG path — where re-encoding
 * through {@code ImageIO} drops everything that isn't pixels by construction —
 * a PDF can carry JavaScript, actions that fire on open, embedded files and
 * form logic, all of which survive a naive copy. Evidence uploads come from
 * students' own devices, so the file is untrusted input in the ordinary
 * sense.</p>
 *
 * <p><b>Rejection over repair.</b> An encrypted or structurally broken PDF is
 * refused rather than salvaged, matching how {@code stripImageMetadata}
 * already treats an undecodable image: refusing an evidence file is a
 * recoverable inconvenience, storing one whose contents nobody could verify
 * is not.</p>
 *
 * <p><b>Bounded parsing.</b> Parsing runs against a temp-file stream cache
 * rather than the heap, so a malformed or hostile document cannot exhaust a
 * 1 vCPU box by being declared enormous. Page count is capped for the same
 * reason — the per-view watermark walks every page.</p>
 */
@Component
public class PdfSanitizer {

    /** Thrown when a PDF cannot be accepted. The message reaches the uploader as a rejection reason. */
    public static class UnacceptablePdfException extends RuntimeException {
        public UnacceptablePdfException(String message) {
            super(message);
        }
    }

    private final int maxPages;

    PdfSanitizer(@Value("${timebank.file.pdf.max-pages:100}") int maxPages) {
        this.maxPages = maxPages;
    }

    /**
     * @return the sanitized document's bytes — never the input array.
     * @throws UnacceptablePdfException if the document is encrypted, corrupt, or over the page cap.
     */
    public byte[] sanitize(byte[] original) {
        // Temp-file stream cache, not heap: see the class comment on bounded parsing.
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(original), "",
                null, null, IOUtils.createTempFileOnlyStreamCache())) {

            if (document.isEncrypted()) {
                // Reached when a document is encrypted but openable with an empty
                // owner password. Still refused: its permission flags are a claim
                // we would have to honour or knowingly ignore, and neither is a
                // sensible thing to do silently with someone's evidence.
                throw new UnacceptablePdfException("Encrypted PDFs are not accepted as evidence.");
            }
            if (document.getNumberOfPages() == 0) {
                throw new UnacceptablePdfException("This PDF has no pages.");
            }
            if (document.getNumberOfPages() > maxPages) {
                throw new UnacceptablePdfException(
                        "This PDF has " + document.getNumberOfPages() + " pages; the limit is " + maxPages + ".");
            }

            stripActiveContent(document);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (UnacceptablePdfException e) {
            throw e;
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new UnacceptablePdfException("Password-protected PDFs are not accepted as evidence.");
        } catch (IOException | RuntimeException e) {
            // Deliberately broad: PDFBox surfaces structural damage as any of
            // IOException, IllegalArgumentException or NPE depending on which
            // object graph the corruption lands in. All of them mean the same
            // thing here — we cannot vouch for this file, so we do not keep it.
            throw new UnacceptablePdfException("This PDF could not be read and was not accepted.");
        }
    }

    private void stripActiveContent(PDDocument document) {
        PDDocumentCatalog catalog = document.getDocumentCatalog();

        // Fires without any user interaction when the document opens — the
        // single most valuable thing to remove.
        catalog.setOpenAction(null);
        // Document-level additional actions (will-close, will-save, ...).
        catalog.getCOSObject().removeItem(COSName.getPDFName("AA"));

        PDDocumentNameDictionary names = catalog.getNames();
        if (names != null) {
            names.setJavascript(null);
            names.setEmbeddedFiles(null);
        }

        // Form fields can carry their own JavaScript in field and widget
        // actions. Evidence is a scan, not a form, so the whole dictionary
        // goes rather than being walked field by field.
        catalog.setAcroForm(null);

        for (PDPage page : document.getPages()) {
            page.setActions(null);
            for (PDAnnotation annotation : annotationsOf(page)) {
                // Covers link/widget/screen actions (/A) and the annotation's
                // own additional-action dictionary (/AA).
                annotation.getCOSObject().removeItem(COSName.A);
                annotation.getCOSObject().removeItem(COSName.getPDFName("AA"));
            }
        }
    }

    private java.util.List<PDAnnotation> annotationsOf(PDPage page) {
        try {
            return page.getAnnotations();
        } catch (IOException e) {
            // A page whose annotation list will not parse is exactly the kind of
            // damage this class refuses rather than works around.
            throw new UnacceptablePdfException("This PDF's annotations could not be read and it was not accepted.");
        }
    }
}
