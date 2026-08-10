package cn.timebank.file;

import cn.timebank.audit.infrastructure.AuditLogRepository;
import cn.timebank.common.error.ApiException;
import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.DownloadResult;
import cn.timebank.file.application.dto.FileMetadataDto;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B3.4/B3.5's exit criteria against real MySQL and real local-disk bytes:
 * the upload → complete → ready pipeline, a magic-byte mismatch rejection,
 * uploader-vs-cross-student access (with the audit entry the latter must
 * produce), and the delete guard once a file is bound to a submitted record.
 */
class FileServiceIT extends AbstractIntegrationTest {

    // Real JPEG signature (FF D8 FF) padded out — BasicFileScanner reads up to 16 header bytes.
    private static final byte[] VALID_JPEG = cn.timebank.testsupport.TestImages.validJpegBytes();
    private static final byte[] NOT_A_JPEG = "this is definitely not a jpeg".getBytes();

    @Autowired
    private FileService fileService;
    @Autowired
    private StudentService studentService;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void uploadCompletePipelineProducesAReadyFileWithAHash() {
        String actorId = "usr_test_admin";
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"), actorId);
        assertThat(intent.fileId()).isNotBlank();

        fileService.receiveContent(intent.fileId(), actorId, new ByteArrayInputStream(VALID_JPEG));
        FileMetadataDto completed = fileService.complete(intent.fileId(), actorId);

        assertThat(completed.status()).isEqualTo("READY");
        assertThat(completed.downloadUrl()).isNotBlank();
    }

    @Test
    void aMimeMismatchIsRejectedWithAReason() {
        String actorId = "usr_test_admin";
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("fake.jpg", "image/jpeg", NOT_A_JPEG.length, "serviceRecordEvidence"), actorId);
        fileService.receiveContent(intent.fileId(), actorId, new ByteArrayInputStream(NOT_A_JPEG));

        FileMetadataDto completed = fileService.complete(intent.fileId(), actorId);
        assertThat(completed.status()).isEqualTo("REJECTED");
        assertThat(completed.rejectionReason()).isEqualTo("MIME_MISMATCH");
        assertThat(completed.downloadUrl()).isNull();
    }

    @Test
    void unsupportedMimeTypeIsRejectedAtIntentTime() {
        assertThatThrownBy(() -> fileService.createUploadIntent(
                new CreateUploadIntentRequest("evil.exe", "application/x-msdownload", 100, "serviceRecordEvidence"),
                "usr_test_admin"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void uploaderCanReadTheirOwnFileButAnUnrelatedStaffCannotWithoutAReferencingRecord() {
        String uploaderId = "usr_file_owner1";
        String fileId = uploadReady(uploaderId);

        FileMetadataDto asOwner = fileService.getMetadata(fileId, authFor(uploaderId, null, Set.of("student")));
        assertThat(asOwner.id()).isEqualTo(fileId);

        assertThatThrownBy(() -> fileService.getMetadata(fileId, authFor("usr_other_teacher", null, Set.of("teacher"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void staffInScopeOfAReferencingRecordCanReadTheFileAndTheAccessIsAudited() {
        StudentDetailDto student = createStudent();
        String uploaderId = "usr_" + student.id().substring(4);
        String fileId = uploadReady(uploaderId);

        createDraftReferencing(student, fileId);

        long before = auditLogRepository.findByResourceTypeAndResourceId("file", fileId, PageRequest.of(0, 20)).getTotalElements();
        FileMetadataDto asAdmin = fileService.getMetadata(fileId, authFor("usr_cross_admin", null, Set.of("admin")));
        assertThat(asAdmin.id()).isEqualTo(fileId);
        long after = auditLogRepository.findByResourceTypeAndResourceId("file", fileId, PageRequest.of(0, 20)).getTotalElements();
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void deleteIsBlockedOnceTheFileIsBoundToASubmittedRecord() {
        StudentDetailDto student = createStudent();
        String uploaderId = "usr_" + student.id().substring(4);
        String fileId = uploadReady(uploaderId);
        ServiceRecordDto draft = createDraftReferencing(student, fileId);

        serviceRecordService.submit(draft.id(), authFor(uploaderId, student.id(), Set.of("student")),
                "idem-file-delete-guard-" + draft.id(), "zh-CN");

        assertThatThrownBy(() -> fileService.delete(fileId, authFor(uploaderId, student.id(), Set.of("student"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void deleteSucceedsWhenOnlyReferencedByADraft() {
        StudentDetailDto student = createStudent();
        String uploaderId = "usr_" + student.id().substring(4);
        String fileId = uploadReady(uploaderId);
        createDraftReferencing(student, fileId);

        fileService.delete(fileId, authFor(uploaderId, student.id(), Set.of("student")));
        assertThatThrownBy(() -> fileService.getMetadata(fileId, authFor(uploaderId, student.id(), Set.of("student"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void statusesOfReturnsReadyForACompletedFileAndOmitsUnknownIds() {
        String fileId = uploadReady("usr_test_admin");
        Map<String, String> statuses = fileService.statusesOf(List.of(fileId, "file_does_not_exist"));
        assertThat(statuses).containsEntry(fileId, "READY");
        assertThat(statuses).doesNotContainKey("file_does_not_exist");
    }

    @Test
    void downloadContentStreamsTheStoredBytesViaAValidToken() throws Exception {
        String actorId = "usr_test_admin";
        String fileId = uploadReady(actorId);
        FileMetadataDto metadata = fileService.getMetadata(fileId, authFor(actorId, null, Set.of("admin")));
        String token = metadata.downloadUrl().substring(metadata.downloadUrl().indexOf("token=") + "token=".length());

        DownloadResult result = fileService.downloadContent(token);
        byte[] downloaded = result.content().readAllBytes();
        // Not byte-identical to the upload: B6.12's EXIF-stripping pass
        // decodes and re-encodes every image on `complete()`, so the stored
        // bytes are a fresh (re-)encoding, not the original upload. What
        // must still hold is that the result is a real, decodable image.
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(downloaded))).isNotNull();
    }

    private String uploadReady(String actorId) {
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"), actorId);
        fileService.receiveContent(intent.fileId(), actorId, new ByteArrayInputStream(VALID_JPEG));
        fileService.complete(intent.fileId(), actorId);
        return intent.fileId();
    }

    private ServiceRecordDto createDraftReferencing(StudentDetailDto student, String fileId) {
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Test Supervisor", "test@example.com",
                List.of(fileId), false, null);
        return serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
    }

    private StudentDetailDto createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_file_" + n, "File Student " + n, null, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1), null, null);
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private Authentication authFor(String userId, String studentId, Set<String> roleCodes) {
        cn.timebank.iam.application.dto.AuthenticatedUserView view = new cn.timebank.iam.application.dto.AuthenticatedUserView(
                userId, userId, "hash", "Test " + userId, true, false, "zh-CN", roleCodes, Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
