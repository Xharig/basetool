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

  /** The scope that marks a token of an offline session. */
  static final String OFFLINE_ACCESS = "offline_access";

  /** The claim holding the time of the sign-in a token descends from. */
  static final String AUTH_TIME = "auth_time";

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
      refuse(
          refusals.clientLabel(jwt.getClaimAsString("azp")),
          response,
          HttpStatus.NOT_FOUND,
          ExchangeRefusals.NOT_FOUND,
          "No such exchange route.");
      return;
    }
    ExchangeRequestContext context;
    try {
      context = admit(jwt, route.get(), request, response);
    } catch (ExchangeUnavailableException e) {
      response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
      refuse(
          refusals.clientLabel(jwt.getClaimAsString("azp")),
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
    String clientId = jwt.getClaimAsString("azp");
    String label = ExchangeRefusals.clientLabel(clientId, registry);
    if (!registry.enabled()) {
      response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
      refuse(
          label,
          response,
          HttpStatus.SERVICE_UNAVAILABLE,
          ExchangeRefusals.EXCHANGE_DISABLED,
          "The exchange is switched off.");
      return null;
    }
    ExchangeRegistry.Client client = clientId == null ? null : registry.clients().get(clientId);
    if (client == null) {
      refuse(
          label,
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.CLIENT_NOT_ALLOWED,
          "This client is not approved for the exchange.");
      return null;
    }
    if (!client.active()) {
      refuse(
          label,
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
          label,
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.UNAUTHENTICATED,
          "The token names no member or key.");
      return null;
    }
    if (revocationReader.isDenied(thumbprint)) {
      refuse(
          label,
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.INSTALLATION_REVOKED,
          "This installation was disconnected; connect again with a new key.");
      return null;
    }
    Set<String> granted = scopes(jwt);
    Long revokedAt = revocationReader.revokedAt(clientId, member);
    if (revokedAt != null && !connectedAfter(jwt, granted, revokedAt)) {
      refuse(
          label,
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.CLIENT_REVOKED,
          "The member disconnected this client after this connection was made.");
      return null;
    }
    granted.retainAll(client.capabilities());
    if (!route.admits(granted)) {
      refuse(
          label,
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.SCOPE_MISSING,
          "This route needs a capability the token or the client does not hold.");
      return null;
    }
    if (!ClientVersions.meets(
        request.getHeader(HttpHeaders.USER_AGENT), client.minClientVersion())) {
      refuse(
          label,
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
   * @param client the {@code client_id} label of the refused request
   * @param response the response
   * @param status the status
   * @param code the problem code
   * @param detail the detail
   * @throws IOException if writing fails
   */
  private void refuse(
      @NotNull String client,
      @NotNull HttpServletResponse response,
      @NotNull HttpStatus status,
      @NotNull String code,
      @NotNull String detail)
      throws IOException {
    refusals.count(code, client);
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
   * Tells whether the token belongs to a connection made after the member disconnected the client
   * (REQ-XCH-008). An offline token is judged by its {@code iat}, because the disconnect ended
   * every offline session of the client; any other token by its {@code auth_time}, which a refresh
   * keeps and only a new sign-in renews. A token lacking the claim it is judged by is refused.
   *
   * @param jwt the token
   * @param scopes the token's scopes
   * @param revokedAt the revocation's epoch second
   * @return {@code true} when the token was issued to a later connection
   */
  static boolean connectedAfter(@NotNull Jwt jwt, @NotNull Set<String> scopes, long revokedAt) {
    Instant moment = scopes.contains(OFFLINE_ACCESS) ? jwt.getIssuedAt() : authTime(jwt);
    return moment != null && moment.getEpochSecond() > revokedAt;
  }

  /**
   * Returns the token's {@code auth_time}.
   *
   * @param jwt the token
   * @return the time of the sign-in the token descends from, or {@code null} when the claim is
   *     absent or not a time
   */
  private static @Nullable Instant authTime(@NotNull Jwt jwt) {
    try {
      return jwt.getClaimAsInstant(AUTH_TIME);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
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
