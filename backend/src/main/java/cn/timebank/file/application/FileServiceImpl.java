package cn.timebank.file.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.file.application.dto.DownloadResult;
import cn.timebank.file.application.dto.FileMetadataDto;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.file.domain.EvidenceFile;
import cn.timebank.file.domain.FileStatus;
import cn.timebank.file.infrastructure.EvidenceFileRepository;
import cn.timebank.file.infrastructure.ServiceRecordReferenceRepository;
import cn.timebank.file.infrastructure.ServiceRecordReferenceRepository.Reference;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
class FileServiceImpl implements FileService {

    private static final List<String> ALLOWED_MIME_TYPES = List.of("image/jpeg", "image/png", "application/pdf");
    private static final int HEADER_SNIFF_BYTES = 16;

    private final EvidenceFileRepository repository;
    private final ServiceRecordReferenceRepository referenceRepository;
    private final FileStorage fileStorage;
    private final FileScanner fileScanner;
    private final DownloadTokenService downloadTokenService;
    private final AuditService auditService;
    private final DataScopeResolver dataScopeResolver;
    private final long maxSizeBytes;

    FileServiceImpl(EvidenceFileRepository repository, ServiceRecordReferenceRepository referenceRepository,
                     FileStorage fileStorage, FileScanner fileScanner, DownloadTokenService downloadTokenService,
                     AuditService auditService, DataScopeResolver dataScopeResolver,
                     @Value("${timebank.file.max-size-bytes:20971520}") long maxSizeBytes) {
        this.repository = repository;
        this.referenceRepository = referenceRepository;
        this.fileStorage = fileStorage;
        this.fileScanner = fileScanner;
        this.downloadTokenService = downloadTokenService;
        this.auditService = auditService;
        this.dataScopeResolver = dataScopeResolver;
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    @Transactional
    public UploadIntentDto createUploadIntent(CreateUploadIntentRequest request, String actorId) {
        if (request.sizeBytes() > maxSizeBytes) {
            throw ApiException.fileTooLarge("Declared size exceeds the maximum allowed (" + maxSizeBytes + " bytes).");
        }
        if (!ALLOWED_MIME_TYPES.contains(request.mimeType())) {
            throw ApiException.invalidRequest("Unsupported MIME type: " + request.mimeType());
        }

        String id = OpaqueIds.generate("file");
        Instant now = Instant.now();
        EvidenceFile file = new EvidenceFile(id, actorId, request.purpose(), request.filename(),
                request.mimeType(), request.sizeBytes(), id, now);
        repository.save(file);

        return new UploadIntentDto(id, "/api/v1/files/" + id + "/content",
                Map.of("Content-Type", request.mimeType()), now.plusSeconds(900));
    }

    @Override
    @Transactional
    public void receiveContent(String fileId, String actorId, InputStream content) {
        EvidenceFile file = findOwnedOrThrow(fileId, actorId);
        if (file.getStatus() != FileStatus.PENDING) {
            throw ApiException.stateConflict("Content has already been received for this file.");
        }
        fileStorage.store(file.getStorageKey(), content);
        file.setActualSizeBytes(fileStorage.size(file.getStorageKey()));
        file.setStatus(FileStatus.PROCESSING);
        file.setUpdatedAt(Instant.now());
    }

    @Override
    @Transactional
    public FileMetadataDto complete(String fileId, String actorId) {
        EvidenceFile file = findOwnedOrThrow(fileId, actorId);
        if (file.getStatus() != FileStatus.PROCESSING) {
            throw ApiException.stateConflict("This file has no uploaded content awaiting completion.");
        }

        byte[] header = readHeader(file.getStorageKey());
        FileScanner.ScanResult result = fileScanner.scan(header, file.getActualSizeBytes(),
                file.getDeclaredSizeBytes(), file.getDeclaredMimeType());

        if (result.accepted()) {
            if (isImageType(file.getDeclaredMimeType())) {
                stripImageMetadata(file);
            }
            file.setSha256Hash(sha256(file.getStorageKey()));
            file.setStatus(FileStatus.READY);
        } else {
            file.setStatus(FileStatus.REJECTED);
            file.setRejectionReason(result.rejectionReason());
        }
        file.setUpdatedAt(Instant.now());
        return toDto(file);
    }

    private boolean isImageType(String mimeType) {
        return "image/jpeg".equals(mimeType) || "image/png".equals(mimeType);
    }

    /**
     * B6.12 security review: evidence photos come straight from a
     * student's phone camera and can carry EXIF GPS coordinates and device
     * identifiers — exactly the kind of minor's-location data this
     * project's privacy posture exists to protect (`06-Legal.md`).
     * Decoding via {@code ImageIO} and re-encoding drops all metadata by
     * construction (nothing here copies {@code IIOMetadata} across) — no
     * new dependency, the JDK's own image codecs are enough. A decode
     * failure is treated as a rejection, not a silent pass-through: safer
     * to refuse an evidence photo than to store one with its metadata
     * intact because stripping failed quietly.
     */
    private void stripImageMetadata(EvidenceFile file) {
        try (InputStream in = fileStorage.open(file.getStorageKey())) {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(in);
            if (image == null) {
                throw new IOException("Could not decode image for metadata stripping");
            }
            String formatName = "image/png".equals(file.getDeclaredMimeType()) ? "png" : "jpg";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, formatName, out);
            fileStorage.store(file.getStorageKey(), new java.io.ByteArrayInputStream(out.toByteArray()));
            file.setActualSizeBytes((long) out.size());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to strip image metadata for file " + file.getId(), e);
        }
    }

    @Override
    @Transactional
    public FileMetadataDto getMetadata(String fileId, Authentication auth) {
        EvidenceFile file = findOrThrow(fileId);
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();

        if (!file.getUploaderId().equals(principal.id())) {
            List<Reference> references = referenceRepository.findRecordsReferencing(fileId);
            boolean inScope = references.stream().anyMatch(ref -> isInScope(ref, principal));
            if (!inScope) {
                throw ApiException.notFound("File not found.");
            }
            auditService.record(new AuditEntry(principal.id(), "serviceRecords.read", "file.crossStudentAccess",
                    "file", fileId, null, null, null));
        }
        return toDto(file);
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadResult downloadContent(String token) {
        String fileId = downloadTokenService.validate(token);
        EvidenceFile file = findOrThrow(fileId);
        if (file.getStatus() != FileStatus.READY) {
            throw ApiException.notFound("File not found.");
        }
        return new DownloadResult(fileStorage.open(file.getStorageKey()), file.getOriginalFilename(), file.getDeclaredMimeType());
    }

    @Override
    @Transactional
    public void delete(String fileId, Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        EvidenceFile file = findOwnedOrThrow(fileId, principal.id());

        boolean boundToSubmitted = referenceRepository.findRecordsReferencing(fileId).stream()
                .anyMatch(ref -> !"DRAFT".equals(ref.status()));
        if (boundToSubmitted) {
            throw ApiException.stateConflict("This file is bound to a submitted record and cannot be deleted.");
        }
        fileStorage.delete(file.getStorageKey());
        repository.delete(file);
    }

    @Override
    @Transactional
    public int forcePurge(List<String> fileIds) {
        List<EvidenceFile> files = repository.findAllById(fileIds);
        for (EvidenceFile file : files) {
            fileStorage.delete(file.getStorageKey());
        }
        repository.deleteAll(files);
        return files.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> statusesOf(List<String> fileIds) {
        return repository.findAllById(fileIds).stream()
                .collect(Collectors.toMap(EvidenceFile::getId, f -> f.getStatus().name()));
    }

    /**
     * B1.7's scope check, applied directly against the denormalized columns
     * {@link ServiceRecordReferenceRepository} already returns — deliberately
     * not a call into `ServiceRecordService` (see that repository's Javadoc
     * on why this would create a module cycle).
     */
    private boolean isInScope(Reference ref, TimeBankPrincipal principal) {
        boolean grantAll = principal.hasSchoolWideVisibility(); // 07-RoleFinetune.md §2.10
        ScopeDescriptor scope = dataScopeResolver.resolve(principal, "serviceRecords", grantAll);
        return switch (scope) {
            case ScopeDescriptor.All ignored -> true;
            case ScopeDescriptor.Self ignored -> ref.studentId().equals(principal.studentId());
            case ScopeDescriptor.Grades grades -> grades.gradeIds().contains(ref.gradeId());
            case ScopeDescriptor.Classes classes -> classes.classIds().contains(ref.classId());
            case ScopeDescriptor.Projects ignored -> false;
        };
    }

    private byte[] readHeader(String storageKey) {
        try (InputStream in = fileStorage.open(storageKey)) {
            return in.readNBytes(HEADER_SNIFF_BYTES);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String sha256(String storageKey) {
        try (InputStream in = fileStorage.open(storageKey)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash evidence file: " + storageKey, e);
        }
    }

    private EvidenceFile findOrThrow(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("File not found."));
    }

    private EvidenceFile findOwnedOrThrow(String id, String actorId) {
        EvidenceFile file = findOrThrow(id);
        if (!file.getUploaderId().equals(actorId)) {
            throw ApiException.notFound("File not found.");
        }
        return file;
    }

    private FileMetadataDto toDto(EvidenceFile f) {
        String downloadUrl = f.getStatus() == FileStatus.READY
                ? "/api/v1/files/" + f.getId() + "/content?token=" + downloadTokenService.issue(f.getId())
                : null;
        return new FileMetadataDto(f.getId(), f.getUploaderId(), f.getPurpose(), f.getOriginalFilename(),
                f.getDeclaredMimeType(), f.getActualSizeBytes() != null ? f.getActualSizeBytes() : f.getDeclaredSizeBytes(),
                f.getStatus().name(), f.getRejectionReason(), downloadUrl, f.getCreatedAt(), f.getUpdatedAt());
    }
}
