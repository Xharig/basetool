/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.ingest.exchange;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.web.ProblemResponseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The registry gate of the exchange routes, after the token gate: the route must exist, the
 * exchange be switched on, the client be active in the registry, neither the installation key nor
 * the client be revoked for this member, the route's capability be both in the token and granted,
 * and the client's version meet its minimum (REQ-XCH-001, -003, -004, -008, -024). An admitted
 * request carries an {@link ExchangeRequestContext}.
 *
 * <p>The registry comes through a five-second cache; the revocations are read on every request.
 * Anything unreadable fails closed with {@code 503 REGISTRY_UNAVAILABLE}.
 */
@RequiredArgsConstructor
public class ExchangeGateFilter extends OncePerRequestFilter {

  /** What a client should wait before retrying a {@code 503}. */
  static final String RETRY_AFTER_SECONDS = "30";

  private final ExchangeRegistryReader registryReader;
  private final ExchangeRevocationReader revocationReader;
  private final ExchangeRefusals refusals;
  private final ObjectMapper objectMapper;
  private final LoggingProperties loggingProperties;
  private final MeterRegistry meterRegistry;

  /**
   * Admits or refuses one authenticated exchange request.
   *
   * @param request the request
   * @param response the response
   * @param filterChain the rest of the chain
   * @throws ServletException if a later filter fails
   * @throws IOException if writing fails
   */
  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    Jwt jwt = token(SecurityContextHolder.getContext().getAuthentication());
    if (jwt == null) {
      filterChain.doFilter(request, response);
      return;
    }
    Optional<ExchangeRoutes.Route> route =
        ExchangeRoutes.find(request.getMethod(), request.getRequestURI());
    if (route.isEmpty()) {
      refuse(response, HttpStatus.NOT_FOUND, ExchangeRefusals.NOT_FOUND, "No such exchange route.");
      return;
    }
    ExchangeRequestContext context;
    try {
      context = admit(jwt, route.get(), request, response);
    } catch (ExchangeUnavailableException e) {
      response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
      refuse(
          response,
          HttpStatus.SERVICE_UNAVAILABLE,
          ExchangeRefusals.REGISTRY_UNAVAILABLE,
          "The exchange registry cannot be read; try again later.");
      return;
    }
    if (context == null) {
      return;
    }
    request.setAttribute(ExchangeRequestContext.ATTRIBUTE, context);
    filterChain.doFilter(request, response);
  }

  /**
   * Runs the registry checks.
   *
   * @param jwt the token
   * @param route the route
   * @param request the request
   * @param response the response a refusal is written to
   * @return the context, or {@code null} when the request was refused
   * @throws IOException if writing a refusal fails
   * @throws ExchangeUnavailableException if the registry or the revocations cannot be read
   */
  private @Nullable ExchangeRequestContext admit(
      @NotNull Jwt jwt,
      @NotNull ExchangeRoutes.Route route,
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response)
      throws IOException {
    ExchangeRegistry registry = registryReader.current();
    if (!registry.enabled()) {
      response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
      refuse(
          response,
          HttpStatus.SERVICE_UNAVAILABLE,
          ExchangeRefusals.EXCHANGE_DISABLED,
          "The exchange is switched off.");
      return null;
    }
    String clientId = jwt.getClaimAsString("azp");
    ExchangeRegistry.Client client = clientId == null ? null : registry.clients().get(clientId);
    if (client == null) {
      refuse(
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.CLIENT_NOT_ALLOWED,
          "This client is not approved for the exchange.");
      return null;
    }
    if (!client.active()) {
      refuse(
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.CLIENT_SUSPENDED,
          "This client is suspended.");
      return null;
    }
    String thumbprint = thumbprint(jwt);
    String member = jwt.getSubject();
    if (thumbprint == null || member == null) {
      refuse(
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.UNAUTHENTICATED,
          "The token names no member or key.");
      return null;
    }
    if (revocationReader.isDenied(thumbprint)) {
      refuse(
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.INSTALLATION_REVOKED,
          "This installation was disconnected; connect again with a new key.");
      return null;
    }
    Long revokedAt = revocationReader.revokedAt(clientId, member);
    Instant issuedAt = jwt.getIssuedAt();
    if (revokedAt != null && (issuedAt == null || issuedAt.getEpochSecond() <= revokedAt)) {
      refuse(
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.CLIENT_REVOKED,
          "The member disconnected this client after the token was issued.");
      return null;
    }
    Set<String> granted = scopes(jwt);
    granted.retainAll(client.capabilities());
    if (!route.admits(granted)) {
      refuse(
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.SCOPE_MISSING,
          "This route needs a capability the token or the client does not hold.");
      return null;
    }
    if (!ClientVersions.meets(
        request.getHeader(HttpHeaders.USER_AGENT), client.minClientVersion())) {
      refuse(
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.CLIENT_VERSION_UNSUPPORTED,
          "This client version is no longer supported; please update.");
      return null;
    }
    return new ExchangeRequestContext(clientId, member, thumbprint, granted, client);
  }

  /**
   * Gates every exchange request except the anonymous contract documents.
   *
   * @param request the current request
   * @return {@code true} to bypass the gate
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return ExchangeTokenGateFilter.isUngated(request);
  }

  /**
   * Writes and counts one refusal.
   *
   * @param response the response
   * @param status the status
   * @param code the problem code
   * @param detail the detail
   * @throws IOException if writing fails
   */
  private void refuse(
      @NotNull HttpServletResponse response,
      @NotNull HttpStatus status,
      @NotNull String code,
      @NotNull String detail)
      throws IOException {
    refusals.count(code);
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, code).increment();
    ProblemResponseWriter.write(
        response, objectMapper, loggingProperties, status, "Refused", code, detail);
  }

  /**
   * Returns the caller's token.
   *
   * @param authentication the current authentication
   * @return the token, or {@code null} when the caller is anonymous
   */
  private static @Nullable Jwt token(@Nullable Authentication authentication) {
    return authentication != null && authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
  }

  /**
   * Returns the token's DPoP key thumbprint.
   *
   * @param jwt the token
   * @return the thumbprint, or {@code null}
   */
  private static @Nullable String thumbprint(@NotNull Jwt jwt) {
    Map<String, Object> confirmation = jwt.getClaimAsMap("cnf");
    return confirmation != null && confirmation.get("jkt") instanceof String jkt && !jkt.isBlank()
        ? jkt
        : null;
  }

  /**
   * Returns the token's scopes.
   *
   * @param jwt the token
   * @return a mutable set of the space-separated {@code scope} claim
   */
  private static @NotNull Set<String> scopes(@NotNull Jwt jwt) {
    String scope = jwt.getClaimAsString("scope");
    Set<String> scopes = new HashSet<>();
    if (scope != null) {
      scopes.addAll(Arrays.asList(scope.trim().split("\\s+")));
      scopes.remove("");
    }
    return scopes;
  }
}
