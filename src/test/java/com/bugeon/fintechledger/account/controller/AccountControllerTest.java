package com.bugeon.fintechledger.account.controller;

import com.bugeon.fintechledger.account.domain.AccountStatus;
import com.bugeon.fintechledger.account.dto.AccountResponse;
import com.bugeon.fintechledger.account.dto.BalanceResponse;
import com.bugeon.fintechledger.account.dto.CreateAccountRequest;
import com.bugeon.fintechledger.account.service.AccountService;
import com.bugeon.fintechledger.auth.security.CustomUserDetailsService;
import com.bugeon.fintechledger.auth.security.JwtTokenProvider;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentMatchers;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

/**
 * @WebMvcTest controller slice test for AccountController.
 *
 * ── 왜 실패했는가 ─────────────────────────────────────────────────────────────
 *
 * @WebMvcTest는 @SpringBootApplication을 로드하지 않는다.
 * FintechLedgerApplication에 선언된 @ConfigurationPropertiesScan이 처리되지 않으므로
 * JwtProperties(@ConfigurationProperties)가 Spring Context에 빈으로 등록되지 않는다.
 *
 * 결과 체인:
 *   JwtProperties 빈 없음
 *   → JwtTokenProvider 생성 불가 (생성자: JwtProperties 주입 필요)
 *   → JwtAuthenticationFilter 생성 불가 (JwtTokenProvider 주입 필요)
 *   → "No qualifying bean of type JwtTokenProvider required by JwtAuthenticationFilter"
 *
 * ── 왜 수정 후 통과하는가 ──────────────────────────────────────────────────────
 *
 * @MockBean JwtTokenProvider:
 *   JwtProperties 없이도 JwtTokenProvider 빈이 mock으로 등록된다.
 *   JwtAuthenticationFilter는 mock JwtTokenProvider를 주입받아 정상 생성된다.
 *   SecurityConfig는 실제 JwtAuthenticationFilter 빈을 addFilterBefore()에 등록한다.
 *   (mock 필터가 아닌 실제 필터이므로 IllegalArgumentException 없음)
 *
 * @MockBean CustomUserDetailsService:
 *   JwtAuthenticationFilter의 두 번째 의존성인 CustomUserDetailsService(@Service)는
 *   @WebMvcTest에서 로드되지 않는다.
 *   Mock이 CustomUserDetailsService 타입과 UserDetailsService 인터페이스 양쪽에
 *   등록되어 JwtAuthenticationFilter와 SecurityConfig 양쪽 주입을 모두 해결한다.
 *
 * @AutoConfigureMockMvc(addFilters = false):
 *   SecurityFilterChain은 정상적으로 빌드되지만 MockMvc 요청에 적용되지 않는다.
 *   컨트롤러 단위 테스트이므로 JWT 검증이 필요 없고,
 *   user() post-processor로 @AuthenticationPrincipal을 직접 주입한다.
 */
@WebMvcTest(AccountController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AccountController")
class AccountControllerTest {

    @MockBean JwtTokenProvider         jwtTokenProvider;       // JwtProperties 없이 JwtAuthenticationFilter 생성 가능하게 함
    @MockBean CustomUserDetailsService customUserDetailsService; // JwtAuthenticationFilter + SecurityConfig 주입 해결
    @MockBean AccountService           accountService;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    private UserPrincipal principal;
    private UUID          userId;
    private UUID          accountId;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        accountId = UUID.randomUUID();
        principal = UserPrincipal.of(userId, "user@example.com");
    }

    // ── POST /api/v1/accounts ─────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/accounts")
    class CreateAccount {

        @Test
        @DisplayName("returns 201 with account details on success")
        void returns201OnSuccess() throws Exception {
            AccountResponse response = buildResponse("1001-0000-000001", "Savings");
            System.out.println("MOCK RESPONSE = " + response);
            given(accountService.createAccount(any(CreateAccountRequest.class), org.mockito.ArgumentMatchers.nullable(UserPrincipal.class)))
                    .willReturn(response);

            mockMvc.perform(post("/api/v1/accounts")
                            .with(user(principal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateAccountRequest("Savings", "KRW"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.accountNumber").value("1001-0000-000001"))
                    .andExpect(jsonPath("$.data.accountName").value("Savings"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.balance").value(0));

            verify(accountService).createAccount(any(CreateAccountRequest.class), org.mockito.ArgumentMatchers.nullable(UserPrincipal.class));

            verify(accountService).createAccount(any(), any());
        }

        @Test
        @DisplayName("returns 400 when account name is blank")
        void returns400WhenBlank() throws Exception {
            mockMvc.perform(post("/api/v1/accounts")
                            .with(user(principal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateAccountRequest("", "KRW"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("returns 201 for second account with different number")
        void returns201SecondAccount() throws Exception {
            given(accountService.createAccount(any(CreateAccountRequest.class), org.mockito.ArgumentMatchers.nullable(UserPrincipal.class)))
                    .willReturn(buildResponse("1001-0000-000002", "Emergency Fund"));

            mockMvc.perform(post("/api/v1/accounts")
                            .with(user(principal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateAccountRequest("Emergency Fund", "USD"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.accountNumber").value("1001-0000-000002"));
        }
    }

    // ── GET /api/v1/accounts ──────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/accounts")
    class GetMyAccounts {

        @Test
        @DisplayName("returns 200 with list of accounts")
        void returns200() throws Exception {
            given(accountService.getMyAccounts(org.mockito.ArgumentMatchers.nullable(UserPrincipal.class)))
                    .willReturn(List.of(
                            buildResponse("1001-0000-000001", "Savings"),
                            buildResponse("1001-0000-000002", "Checking")));

            mockMvc.perform(get("/api/v1/accounts").with(user(principal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].accountNumber").value("1001-0000-000001"))
                    .andExpect(jsonPath("$.data[1].accountNumber").value("1001-0000-000002"));
        }

        @Test
        @DisplayName("returns 200 with empty list")
        void returns200Empty() throws Exception {
            given(accountService.getMyAccounts(org.mockito.ArgumentMatchers.nullable(UserPrincipal.class))).willReturn(List.of());

            mockMvc.perform(get("/api/v1/accounts").with(user(principal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ── GET /api/v1/accounts/{accountId} ─────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/accounts/{accountId}")
    class GetAccount {

        @Test
        @DisplayName("returns 200 for owner")
        void returns200() throws Exception {
            given(accountService.getAccount(any(UUID.class), org.mockito.ArgumentMatchers.nullable(UserPrincipal.class)))
                    .willReturn(buildResponse("1001-0000-000001", "Savings"));

            mockMvc.perform(get("/api/v1/accounts/{id}", accountId).with(user(principal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accountNumber").value("1001-0000-000001"));
        }

        @Test
        @DisplayName("returns 404 when account does not exist")
        void returns404() throws Exception {
            given(accountService.getAccount(any(UUID.class), org.mockito.ArgumentMatchers.nullable(UserPrincipal.class)))
                    .willThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

            mockMvc.perform(get("/api/v1/accounts/{id}", accountId).with(user(principal)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACC_001"));
        }

        @Test
        @DisplayName("returns 403 when not owner")
        void returns403() throws Exception {
            given(accountService.getAccount(any(UUID.class), org.mockito.ArgumentMatchers.nullable(UserPrincipal.class)))
                    .willThrow(new BusinessException(ErrorCode.ACCOUNT_ACCESS_DENIED));

            mockMvc.perform(get("/api/v1/accounts/{id}", accountId).with(user(principal)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACC_002"));
        }
    }

    // ── GET /api/v1/accounts/{accountId}/balance ──────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/accounts/{accountId}/balance")
    class GetBalance {

        @Test
        @DisplayName("returns 200 with zero balance for new account")
        void returns200() throws Exception {
            given(accountService.getBalance(any(UUID.class), org.mockito.ArgumentMatchers.nullable(UserPrincipal.class)))
                    .willReturn(new BalanceResponse(accountId, "1001-0000-000001", BigDecimal.ZERO, "KRW"));

            mockMvc.perform(get("/api/v1/accounts/{id}/balance", accountId).with(user(principal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.balance").value(0))
                    .andExpect(jsonPath("$.data.currency").value("KRW"))
                    .andExpect(jsonPath("$.data.accountNumber").value("1001-0000-000001"));
        }

        @Test
        @DisplayName("returns 404 when account does not exist")
        void returns404() throws Exception {
            given(accountService.getBalance(any(UUID.class), org.mockito.ArgumentMatchers.nullable(UserPrincipal.class)))
                    .willThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

            mockMvc.perform(get("/api/v1/accounts/{id}/balance", accountId).with(user(principal)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 403 when not owner")
        void returns403() throws Exception {
            given(accountService.getBalance(any(UUID.class), org.mockito.ArgumentMatchers.nullable(UserPrincipal.class)))
                    .willThrow(new BusinessException(ErrorCode.ACCOUNT_ACCESS_DENIED));

            mockMvc.perform(get("/api/v1/accounts/{id}/balance", accountId).with(user(principal)))
                    .andExpect(status().isForbidden());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AccountResponse buildResponse(String number, String name) {
        return new AccountResponse(accountId, number, name, "KRW",
                AccountStatus.ACTIVE, BigDecimal.ZERO, Instant.now(), Instant.now());
    }
}
