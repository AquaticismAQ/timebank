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
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
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
 *
 * <h2>The font tests, and what they are worth</h2>
 *
 * <p>The tests this file used to carry all passed while Chinese names printed
 * as empty boxes in production, because they only asked "did the render throw?"
 * and the answer on a macOS build machine — which ships a CJK system font — was
 * no. The four below are written so that they would have been <b>red</b> on
 * that same machine against that same code. Each says in its own javadoc what
 * it does and does not establish; between them they cover "the glyphs are in
 * the file" and "nothing asks the operating system for a font", which is the
 * pair that makes the render host-independent.</p>
 */
class CredentialSlipDocumentTest {

    private static final Instant ISSUED = Instant.parse("2026-09-17T09:30:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-09-24T09:30:00Z");
    private static final String ROOT = "https://tb.example.com";
    private static final String EN = "en";
    private static final String ZH = "zh-CN";

    /**
     * Invented, and deliberately not name-shaped: every fixture in this repo has
     * to be safe to publish (Docs/README.md §2.8), and a plausible Chinese name
     * is exactly the kind of string that reads as real. These two are literally
     * "example classmate" and "test student", and they carry the property the
     * test needs — not one character of either is encodable in WinAnsi or
     * present in the deployment host's DejaVu fonts.
     */
    private static final String CJK_NAME = "同学示例";
    private static final String OTHER_CJK_NAME = "测试学生";

    /**
     * §2.8's rule, and the reason it exists: "the sheet may be cut, and a
     * page-level mark survives on exactly one piece". Two slips share one page
     * here, so a single provenance line per page would still look right in a
     * viewer and be wrong the moment scissors touch it.
     */
    @Test
    void provenanceIsPrintedOnEverySlipNotOncePerPage() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "AAAAAAAA1"),
                slip("22222222", "BBBBBBBB2")), SlipsPerPage.TWO, EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages())
                    .as("two slips at two per page is one sheet")
                    .isEqualTo(1);
            assertThat(occurrences(pageText(document, 1), printed(CredentialSlipDocument.provenanceMarker(EN))))
                    .as("one provenance line per slip on the page, not one per page")
                    .isEqualTo(2);
        }
    }

    /** The same rule across a multi-page batch: every slip, on every sheet. */
    @Test
    void everySlipOnEveryPageCarriesItsOwnProvenance() throws IOException {
        List<Slip> slips = List.of(slip("11111111", "AAAAAAAA1"), slip("22222222", "BBBBBBBB2"),
                slip("33333333", "CCCCCCCC3"), slip("44444444", "DDDDDDDD4"));
        byte[] pdf = CredentialSlipDocument.render(slips, SlipsPerPage.TWO, EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            for (int page = 1; page <= 2; page++) {
                assertThat(occurrences(pageText(document, page), printed(CredentialSlipDocument.provenanceMarker(EN))))
                        .as("provenance count on page %d", page)
                        .isEqualTo(2);
            }
        }
    }

    /** §2.8's default: one per page, one page each, provenance still per slip. */
    @Test
    void oneSlipPerPageIsTheDefaultLayoutAndStillCarriesProvenance() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(
                List.of(slip("11111111", "AAAAAAAA1"), slip("22222222", "BBBBBBBB2")), SlipsPerPage.ONE, EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(occurrences(pageText(document, 1), printed(CredentialSlipDocument.provenanceMarker(EN)))).isEqualTo(1);
            assertThat(occurrences(pageText(document, 2), printed(CredentialSlipDocument.provenanceMarker(EN)))).isEqualTo(1);
        }
    }

    /** §2.8: "issued by, at, valid before" — all three, on the printed line. */
    @Test
    void provenanceNamesTheIssuerTheIssueTimeAndTheExpiry() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "AAAAAAAA1")), SlipsPerPage.ONE, EN);

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
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "HKTZ49QP2M")), SlipsPerPage.ONE, EN);

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
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "HKTZ49QP2M")), SlipsPerPage.ONE, EN);

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
        byte[] pdf = CredentialSlipDocument.render(List.of(slip("11111111", "HKTZ49QP2M")), SlipsPerPage.ONE, EN);

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
                List.of(slip("11111111", "HKTZ49QP2M"), slip("22222222", "8XR3TN5W0")), SlipsPerPage.TWO, EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> decoded = decodeQrCodes(new PDFRenderer(document).renderImageWithDPI(0, 200));
            assertThat(decoded).containsExactlyInAnyOrder(
                    ROOT + "/activate?code=HKTZ49QP2M",
                    ROOT + "/activate?code=8XR3TN5W0");
        }
    }

    /**
     * <b>Proof (a): a Chinese name is text in the PDF, not a picture of text.</b>
     *
     * <p>{@link PDFTextStripper} reads the content stream's {@code Tj} operands
     * back through the font's {@code ToUnicode} map. A rasterised name — the old
     * behaviour — contributes <em>nothing</em> to that stream, so this assertion
     * is red against the previous implementation on any machine, CJK system font
     * or not. It is also what the requirement actually asks for: a name a
     * teacher can search for, select and copy out of the document.</p>
     *
     * <p><b>What it does not prove:</b> that the glyphs look right. Text
     * extraction round-trips through {@code ToUnicode}, so a font embedded with
     * a correct character map and wrong outlines would still pass here. The
     * companion for that is the subset test below, which checks the embedded
     * program is the bundled file.</p>
     */
    @Test
    void aChineseNameIsExtractableTextRatherThanARasterisedImage() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(namedSlip(CJK_NAME, "11111111", "HKTZ49QP2M")),
                SlipsPerPage.ONE, EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(pageText(document, 1))
                    .as("the student's own name, selectable in the finished PDF")
                    .contains(printed(CJK_NAME));
        }
    }

    /**
     * The same, for a batch: a class is thirty different names, and a per-slip
     * regression that only printed the first one would pass the single-slip
     * test above.
     */
    @Test
    void everySlipInABatchCarriesItsOwnChineseNameAsText() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(
                List.of(namedSlip(CJK_NAME, "11111111", "HKTZ49QP2M"),
                        namedSlip(OTHER_CJK_NAME, "22222222", "8XR3TN5W0")),
                SlipsPerPage.TWO, EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = pageText(document, 1);
            assertThat(text).contains(printed(CJK_NAME)).contains(printed(OTHER_CJK_NAME));
        }
    }

    /**
     * <b>Proof (b): the fixed labels print in Chinese when the request resolved
     * to Chinese.</b>
     *
     * <p>Standard-14 Helvetica cannot encode any of these strings at all —
     * {@code showText} throws on them — so before the embedded font this test
     * could not have been made to pass by any amount of system-font luck. The
     * strings asserted are the ones §2.9 requires to match the screen word for
     * word, checked against the frontend catalogue's own values.</p>
     */
    @Test
    void chineseLabelsArePrintedWhenTheRequestResolvedToChinese() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(namedSlip(CJK_NAME, "11111111", "HKTZ49QP2M")),
                SlipsPerPage.ONE, ZH);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = pageText(document, 1);
            assertThat(text)
                    .as("auth.login.firstTimeEntry, verbatim — §2.9's 'the screen and the paper say the same words'")
                    .contains(printed("首次登录？使用激活码"));
            assertThat(text).as("auth.activation.codeLabel").contains(printed("激活码"));
            assertThat(text).as("the name label").contains(printed("姓名"));
            assertThat(text)
                    // The second limb is the same PDFBox reverse-cmap alias described on
                    // pageText, in the one form NFKC cannot undo: 户 and its traditional
                    // twin 戶 share a glyph, and PDFBox names the twin. They are separate
                    // code points rather than a compatibility pair, so there is nothing to
                    // normalise. Accepting both keeps the assertion about the slip rather
                    // than about PDFBox's extraction, and keeps it green if PDFBox improves.
                    .as("auth.login.accountPlaceholder")
                    .containsAnyOf(printed("用户名"), printed("用戶名"));
            assertThat(text).as("§2.8's provenance line, in Chinese").contains(printed("签发人：Example Admin"));
            assertThat(text).contains(printed("有效期至：2026-09-24 09:30 UTC"));
            assertThat(text)
                    .as("a Chinese slip must not leave English labels stranded on it")
                    .doesNotContain("Activation code")
                    .doesNotContain("Issued by ");
        }
    }

    /** The mirror image: an English request still gets an all-English slip. */
    @Test
    void englishLabelsArePrintedWhenTheRequestResolvedToEnglish() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(namedSlip(CJK_NAME, "11111111", "HKTZ49QP2M")),
                SlipsPerPage.ONE, EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = pageText(document, 1);
            assertThat(text).contains("First time logging in? Use your activation code");
            assertThat(text).contains("Activation code").contains("Username").contains("Name");
            assertThat(text)
                    .as("the student's name is theirs and is not translated away")
                    .contains(printed(CJK_NAME));
            assertThat(text).doesNotContain(printed("激活码"));
        }
    }

    /**
     * <b>Proof (c): the embedded font is a subset.</b>
     *
     * <p>{@code PDType0Font.load(doc, stream, embedSubset)} takes a boolean, and
     * flipping it to {@code false} breaks nothing visible: every other
     * assertion in this file still passes, the PDF still renders, and every
     * download silently becomes 17 MB — thirty slips for one class, once per
     * class, per term. This is the assertion that makes that flip red.</p>
     *
     * <p>Both halves matter. The whole-document ceiling is what an admin
     * actually experiences, and the {@code FontFile2} ceiling is what pins the
     * cause to the font rather than to the QR images, so a future reader of a
     * failure knows which one grew.</p>
     */
    @Test
    void theEmbeddedFontIsSubsetRatherThanTheWholeSeventeenMegabyteProgram() throws IOException {
        List<Slip> classOfThirty = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            classOfThirty.add(namedSlip(CJK_NAME + i, String.format("2000%04d", i), String.format("HKTZ4%05d", i)));
        }

        byte[] pdf = CredentialSlipDocument.render(classOfThirty, SlipsPerPage.ONE, ZH);

        assertThat(SlipFont.programByteCount())
                .as("the source font really is the large one this test exists to guard against")
                .isGreaterThan(10_000_000);
        assertThat(pdf.length)
                .as("a whole class's slips, well under a megabyte")
                .isLessThan(1_000_000);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(30);
            List<Integer> programs = embeddedFontProgramSizes(document);
            assertThat(programs)
                    .as("exactly one embedded font program for the whole batch, not one per page")
                    .hasSize(1);
            assertThat(programs.getFirst())
                    .as("a subset of the glyphs actually used, not the 17 MB source")
                    .isLessThan(500_000)
                    .isGreaterThan(1_000);
        }
    }

    /**
     * <b>Proof (d), part one: nothing in the text path asks AWT to find a
     * font.</b>
     *
     * <p>This is the structural half, and it is structural because the real
     * condition cannot be forced here — macOS ships Hiragino Sans GB and the
     * test JVM cannot be made to forget it. So instead of simulating a
     * font-less host, this asserts the classes that build the document have no
     * reference to the API that consults the host at all: once
     * {@code java.awt.Font} and {@code Graphics2D} are absent, "which fonts are
     * installed" is a question this code has no way to ask.</p>
     *
     * <p><b>The exclusion is deliberate and is not a hole.</b>
     * {@code BufferedImage} stays permitted because the QR genuinely is an
     * image — {@code QrCodeImage} paints a {@code BitMatrix} pixel by pixel,
     * with no font involved. {@code java.awt.Color} stays permitted because it
     * is PDFBox's own colour type. What is banned is exactly the glyph-
     * rasterisation surface: {@code Font}, {@code FontMetrics},
     * {@code Graphics}/{@code Graphics2D} (which is where {@code drawString}
     * lives), {@code RenderingHints} and {@code java.awt.font}.</p>
     *
     * <p><b>What it does not prove:</b> that PDFBox itself never falls back to a
     * system font internally. It cannot — that is PDFBox's code, not ours. The
     * runtime half below is what closes that, by showing the document carries
     * its own font program.</p>
     */
    @Test
    void theSlipBuilderCannotReachAnyAwtFontRenderingApi() {
        noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("java.awt.Font")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.awt.FontMetrics")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.awt.Graphics")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.awt.Graphics2D")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.awt.RenderingHints")
                .orShould().dependOnClassesThat().resideInAPackage("java.awt.font..")
                .because("a slip's text must come from the bundled font file, never from whatever "
                        + "typefaces the deployment host happens to have installed")
                .check(new ClassFileImporter().importClasses(
                        CredentialSlipDocument.class, SlipFont.class, SlipCopy.class));
    }

    /**
     * <b>Proof (d), part two: the finished document carries its own glyphs.</b>
     *
     * <p>Read entirely from the PDF. A one-slip page holds exactly <em>one</em>
     * image XObject — the QR — which is the direct refutation of the old
     * behaviour, where the name was a second image on the same page. The font
     * it uses is a composite font with an embedded {@code FontFile2} whose
     * {@code BaseFont} names the bundled file, so the glyphs travel inside the
     * document and a viewer on a host with no CJK font installed has
     * everything it needs.</p>
     *
     * <p><b>What it does not prove:</b> that the printed page is visually
     * correct on the deployment host — no test running on this machine can
     * establish that. It proves the document is self-contained, which is the
     * property that makes the host irrelevant.</p>
     */
    @Test
    void theFinishedDocumentCarriesItsOwnGlyphsAndOnlyTheQrIsAnImage() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(List.of(namedSlip(CJK_NAME, "11111111", "HKTZ49QP2M")),
                SlipsPerPage.ONE, ZH);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDPage page = document.getPage(0);
            assertThat(imageCount(page.getResources()))
                    .as("the QR, and nothing else — a drawn name would be a second image here")
                    .isEqualTo(1);

            List<PDType0Font> composite = new ArrayList<>();
            for (COSName name : page.getResources().getFontNames()) {
                PDFont font = page.getResources().getFont(name);
                if (font instanceof PDType0Font type0) {
                    composite.add(type0);
                }
            }
            assertThat(composite).as("the slip's human-readable text uses a composite font").hasSize(1);

            PDType0Font font = composite.getFirst();
            assertThat(font.getName())
                    .as("the glyphs come from the bundled file, identified by name in the PDF")
                    .contains("NotoSansSC");
            assertThat(font.getDescendantFont().getFontDescriptor().getFontFile2())
                    .as("a TrueType program embedded in the document, not a reference to a host font")
                    .isNotNull();
        }
    }

    /**
     * A name containing a character the bundled font has no glyph for must not
     * fail the other twenty-nine students' slips. {@code U+E000} is a private-use
     * code point — no font is required to have it, and Noto Sans SC does not.
     */
    @Test
    void aCharacterTheBundledFontLacksIsSubstitutedRatherThanFailingTheBatch() throws IOException {
        byte[] pdf = CredentialSlipDocument.render(
                List.of(namedSlip(CJK_NAME + "\uE000", "11111111", "HKTZ49QP2M")), SlipsPerPage.ONE, ZH);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = pageText(document, 1);
            assertThat(text).as("the readable part of the name survives").contains(printed(CJK_NAME));
            assertThat(text).as("the slip still prints in full").contains(printed("激活码"));
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
        return namedSlip("Example Student " + username, username, code);
    }

    private static Slip namedSlip(String displayName, String username, String code) {
        return new Slip(displayName, username, code, ROOT + "/activate?code=" + code, ROOT,
                "Example Admin", ISSUED, EXPIRES);
    }

    /**
     * The page's text, in NFKC.
     *
     * <h4>Why normalising is necessary, and why it does not weaken anything</h4>
     *
     * <p>PDFBox builds a subset font's {@code ToUnicode} map by reversing the
     * font's own character map: for each embedded glyph it asks which code
     * points reach it and writes down {@code codes.get(0)}, the lowest. Noto
     * Sans SC — like every pan-CJK font — reaches one glyph from both the CJK
     * ideograph and its Kangxi radical twin, and the radical block sits lower,
     * so 示 (U+793A) comes back out as ⽰ (U+2F70). The <em>drawn</em> glyph is
     * correct; only the extraction alias is not. It affects a lot of common
     * characters (首, 用, 示, 生, 人, 方, 网, 至 among them), so picking fixture
     * names that dodge it would be picking fixtures to suit the tool.</p>
     *
     * <p>NFKC's compatibility decomposition folds each Kangxi radical back onto
     * its ideograph, which is exactly the alias to undo. It cannot manufacture
     * a match: a rasterised name contributes no text operators at all, so the
     * assertions that use this still fail against the old bitmap path. The
     * limitation is PDFBox's, is recorded in Docs/issues/087, and does not
     * affect what a printer puts on the paper.</p>
     */
    private static String pageText(PDDocument document, int oneBasedPage) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(oneBasedPage);
        stripper.setEndPage(oneBasedPage);
        return printed(stripper.getText(document));
    }

    /** Puts an expected literal into the same normal form {@link #pageText} returns. */
    private static String printed(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static int imageCount(PDResources resources) throws IOException {
        int images = 0;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject) {
                images++;
            }
        }
        return images;
    }

    /** Byte length of every embedded TrueType program in the document, deduplicated by object. */
    private static List<Integer> embeddedFontProgramSizes(PDDocument document) throws IOException {
        List<Integer> sizes = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        for (PDPage page : document.getPages()) {
            for (COSName name : page.getResources().getFontNames()) {
                PDFont font = page.getResources().getFont(name);
                if (!(font instanceof PDType0Font type0)) {
                    continue;
                }
                var program = type0.getDescendantFont().getFontDescriptor().getFontFile2();
                if (program == null) {
                    continue;
                }
                Object key = program.getCOSObject();
                if (seen.stream().anyMatch(s -> s == key)) {
                    continue;
                }
                seen.add(key);
                sizes.add(program.toByteArray().length);
            }
        }
        return sizes;
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
