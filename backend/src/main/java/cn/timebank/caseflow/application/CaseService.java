package cn.timebank.caseflow.application;

import cn.timebank.caseflow.application.dto.CaseDecisionDto;
import cn.timebank.caseflow.application.dto.CaseDto;
import cn.timebank.caseflow.application.dto.CaseRequests.CreateCaseRequest;
import cn.timebank.caseflow.application.dto.CaseRequests.DecideRequest;
import cn.timebank.caseflow.application.dto.CaseRequests.UpdateCaseRequest;
import cn.timebank.caseflow.application.dto.RevisionDto;
import cn.timebank.caseflow.application.dto.TimelineEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;

/** The `caseflow` module's published interface (Design.md §6). Hosts B5.2's manual-correction-to-ledger effect. */
public interface CaseService {

    /** {@code status}/{@code type}/{@code search} are contract §2.7's filter/search (Docs/clarification/04-DataViewOptimization.md §4.1) — {@code search} matches case title. */
    Page<CaseDto> list(Pageable pageable, Authentication auth, String status, String type, String search, String resolvedLanguage);

    CaseDto getDetail(String id, Authentication auth, String resolvedLanguage);

    CaseDto createDraft(CreateCaseRequest request, Authentication auth, String resolvedLanguage);

    CaseDto updateDraft(String id, UpdateCaseRequest request, Authentication auth, String resolvedLanguage);

    CaseDto submit(String id, Authentication auth, String idempotencyKey, String resolvedLanguage);

    List<RevisionDto> revisions(String id, Authentication auth);

    List<TimelineEntryDto> timeline(String id, Authentication auth);

    /**
     * B5.2: the atomic resolution transaction — on {@code resolve} for a
     * {@code manualCorrection} case only, also appends a ledger transaction
     * via {@code AccountService.adjust}. Every other case type/decision is
     * communication + tracking only (Design.md Q6 — "no new resource, no
     * new workflow" beyond the case type itself).
     */
    CaseDto decide(String id, DecideRequest request, Authentication auth, String idempotencyKey, String resolvedLanguage);

    List<CaseDecisionDto> decisionsForCase(String id);
}
