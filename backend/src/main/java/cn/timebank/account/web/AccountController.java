package cn.timebank.account.web;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountDto;
import cn.timebank.account.application.dto.AccountRequests.AdjustmentRequest;
import cn.timebank.account.application.dto.AccountRequests.FreezeRequest;
import cn.timebank.account.application.dto.AccountRequests.ReverseRequest;
import cn.timebank.account.application.dto.AccountTransactionDto;
import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Contract §4.11. "Owner" access is enforced by the scoped {@link
 * org.springframework.data.jpa.domain.Specification} inside {@code
 * AccountServiceImpl}, not a controller-level SpEL clause — the same
 * B1.7/B1.8 data-scope mechanism `servicerecord` wires in, applied here to
 * its second real entity.
 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private static final Set<String> SORTABLE = Set.of("id", "balance", "createdAt");
    private static final Set<String> TRANSACTION_SORTABLE = Set.of("id", "createdAt");

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('accounts.read')")
    public ApiResponse<List<AccountDto>> list(PageQuery query, Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<AccountDto> page = accountService.list(pageable, auth);
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('accounts.read')")
    public ApiResponse<AccountDto> get(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(accountService.getById(id, auth));
    }

    @GetMapping("/accounts/{id}/transactions")
    @PreAuthorize("hasAuthority('accounts.read')")
    public ApiResponse<List<AccountTransactionDto>> transactions(
            @PathVariable String id, PageQuery query, Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, TRANSACTION_SORTABLE);
        Page<AccountTransactionDto> page = accountService.listTransactions(id, pageable, auth);
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/account-transactions/{id}")
    @PreAuthorize("hasAuthority('accounts.read')")
    public ApiResponse<AccountTransactionDto> transaction(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(accountService.getTransaction(id, auth));
    }

    @PostMapping("/accounts/{id}/adjustments")
    @PreAuthorize("hasAuthority('accounts.adjust')")
    public ApiResponse<AccountTransactionDto> adjust(
            @PathVariable String id, @Valid @RequestBody AdjustmentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication auth) {
        return ApiResponse.of(accountService.adjust(id, request, principalId(auth), idempotencyKey));
    }

    @PostMapping("/account-transactions/{id}/reverse")
    @PreAuthorize("hasAuthority('accounts.reverse')")
    public ApiResponse<AccountTransactionDto> reverse(
            @PathVariable String id, @Valid @RequestBody ReverseRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication auth) {
        return ApiResponse.of(accountService.reverse(id, request, principalId(auth), idempotencyKey));
    }

    @PostMapping("/accounts/{id}/freeze")
    @PreAuthorize("hasAuthority('accounts.freeze')")
    public ApiResponse<AccountDto> freeze(
            @PathVariable String id, @Valid @RequestBody FreezeRequest request, Authentication auth) {
        return ApiResponse.of(accountService.freeze(id, request, principalId(auth)));
    }

    @PostMapping("/accounts/{id}/unfreeze")
    @PreAuthorize("hasAuthority('accounts.freeze')")
    public ApiResponse<AccountDto> unfreeze(
            @PathVariable String id, @Valid @RequestBody FreezeRequest request, Authentication auth) {
        return ApiResponse.of(accountService.unfreeze(id, request, principalId(auth)));
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
