package com.bugeon.fintechledger.auth.security;

import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.common.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Invoked by Spring Security when an unauthenticated request reaches a protected endpoint.
 *
 * Without this bean, Spring Security returns a 302 redirect to a login page
 * or a plain HTML 401 body — neither is appropriate for a REST API.
 *
 * This implementation writes a structured {@link ApiResponse} JSON body with
 * {@code HTTP 401} so clients receive the same error envelope as all other failures.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest      request,
                         HttpServletResponse     response,
                         AuthenticationException authException) throws IOException {

        log.debug("Unauthenticated request to [{}]: {}",
                  request.getRequestURI(), authException.getMessage());

        ErrorCode error = ErrorCode.TOKEN_INVALID;

        response.setStatus(error.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiResponse<Void> body = ApiResponse.fail(new ApiResponse.Error(
                error.getCode(),
                error.getDefaultMessage(),
                null
        ));

        objectMapper.writeValue(response.getWriter(), body);
    }
}
