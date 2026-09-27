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

import static de.greluc.krt.profit.basetool.ingest.exchange.ExchangeTestSupport.BLUEPRINT_CHANGES;
import static de.greluc.krt.profit.basetool.ingest.exchange.ExchangeTestSupport.CLIENT;
import static de.greluc.krt.profit.basetool.ingest.exchange.ExchangeTestSupport.SERVICE_DOCUMENT;
import static de.greluc.krt.profit.basetool.ingest.exchange.ExchangeTestSupport.STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.service.BackendImportClient;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The exchange registry gate end to end: route, switch, client, revocations, capabilities and
 * version (REQ-XCH-001, -003, -004, -008, -024).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ExchangeTestSupport.ProbeRoutes.class)
class ExchangeGateTest {

  private static final String TOKEN = "gate-token";
  private static final String USER_AGENT = "VerseKit/2.4.0 (+https://example.org/versekit)";

  @Autowired private WebApplicationContext context;
  @Autowired private MeterRegistry meterRegistry;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private BackendImportClient backendImportClient;
  @MockitoBean private HandoffStagingService handoffStagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;

  private MockMvc mockMvc;
  private ECKey key;
  private String thumbprint;
  private String member;
  private Instant issuedAt;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    key = ExchangeTestSupport.newKey();
    thumbprint = ExchangeTestSupport.thumbprint(key);
    member = UUID.randomUUID().toString();
    issuedAt = Instant.now().minusSeconds(60);
    tokenScopes("exchange.connect exchange.stock.read");
    registry(true, true, Set.of("exchange.connect", "exchange.stock.read"), null);
    when(revocationReader.isDenied(anyString())).thenReturn(false);
    when(revocationReader.revokedAt(anyString(), anyString())).thenReturn(null);
  }

  @Test
  void anAdmittedRequestCarriesTheClientAndTheCapabilitiesBothHold() throws Exception {
    tokenScopes("exchange.connect exchange.stock.read exchange.hangar.read");
    registry(
        true,
        true,
        Set.of("exchange.connect", "exchange.stock.read", "exchange.demand.read"),
        null);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isOk())
        .andExpect(content().string(CLIENT + " exchange.connect exchange.stock.read"));
  }

  @Test
  void anUnknownRouteIsNotFound() throws Exception {
    call(HttpMethod.GET, "/exchange/v1/me/secrets")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void aKnownPathWithTheWrongMethodIsNotFound() throws Exception {
    call(HttpMethod.POST, STOCK).andExpect(status().isNotFound());
  }

  @Test
  void theSwitchOffRefusesWithRetryAfter() throws Exception {
    registry(false, true, Set.of("exchange.connect", "exchange.stock.read"), null);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
        .andExpect(jsonPath("$.code").value("EXCHANGE_DISABLED"));
  }

  @Test
  void anUnreadableRegistryFailsClosed() throws Exception {
    when(registryReader.current()).thenThrow(new ExchangeUnavailableException("down", null));
    double before = refused("registry_unavailable");

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("REGISTRY_UNAVAILABLE"));

    assertThat(refused("registry_unavailable") - before).isEqualTo(1.0d);
  }

  @Test
  void unreadableRevocationsFailClosed() throws Exception {
    when(revocationReader.isDenied(anyString()))
        .thenThrow(new ExchangeUnavailableException("down", null));

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("REGISTRY_UNAVAILABLE"));
  }

  @Test
  void aClientOutsideTheRegistryIsNotAllowed() throws Exception {
    when(registryReader.current()).thenReturn(new ExchangeRegistry(1L, true, Map.of()));

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
  }

  @Test
  void aSuspendedClientIsRefused() throws Exception {
    registry(true, false, Set.of("exchange.connect", "exchange.stock.read"), null);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CLIENT_SUSPENDED"));
  }

  @Test
  void aDeniedInstallationIsRefusedWhateverTheTokensAge() throws Exception {
    when(revocationReader.isDenied(thumbprint)).thenReturn(true);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INSTALLATION_REVOKED"));
  }

  @Test
  void aTokenIssuedBeforeTheClientRevocationIsRefused() throws Exception {
    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(issuedAt.getEpochSecond() + 10);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("CLIENT_REVOKED"));
  }

  @Test
  void aFreshConnectionAfterTheRevocationWorksAtOnce() throws Exception {
    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(issuedAt.getEpochSecond() - 10);

    call(HttpMethod.GET, STOCK).andExpect(status().isOk());
  }

  @Test
  void aCapabilityMissingFromTheTokenIsRefused() throws Exception {
    tokenScopes("exchange.connect");
    registry(true, true, Set.of("exchange.connect", "exchange.blueprints.write"), null);

    call(HttpMethod.POST, BLUEPRINT_CHANGES)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void aCapabilityTheRegistryDoesNotGrantIsRefused() throws Exception {
    tokenScopes("exchange.connect exchange.blueprints.write");
    registry(true, true, Set.of("exchange.connect", "exchange.stock.read"), null);

    call(HttpMethod.POST, BLUEPRINT_CHANGES)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void aCapabilityBothHoldIsAdmitted() throws Exception {
    tokenScopes("exchange.connect exchange.blueprints.write");
    registry(true, true, Set.of("exchange.connect", "exchange.blueprints.write"), null);

    call(HttpMethod.POST, BLUEPRINT_CHANGES).andExpect(status().isOk());
  }

  @Test
  void theServiceDocumentNeedsConnect() throws Exception {
    tokenScopes("exchange.blueprints.read");

    call(HttpMethod.GET, SERVICE_DOCUMENT)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void anOldVersionIsRefusedAndACurrentOneAdmitted() throws Exception {
    registry(true, true, Set.of("exchange.connect", "exchange.stock.read"), "2.5.0");

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CLIENT_VERSION_UNSUPPORTED"));

    registry(true, true, Set.of("exchange.connect", "exchange.stock.read"), "2.4.0");
    call(HttpMethod.GET, STOCK).andExpect(status().isOk());
  }

  /**
   * Stubs the registry.
   *
   * @param enabled the switch
   * @param active the client's status
   * @param capabilities the grants
   * @param minVersion the minimum version, or {@code null}
   */
  private void registry(
      boolean enabled,
      boolean active,
      @NotNull Set<String> capabilities,
      @Nullable String minVersion) {
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registry(enabled, active, capabilities, minVersion));
  }

  /**
   * Stubs the decoded token with the given scopes.
   *
   * @param scopes the space-separated scopes
   */
  private void tokenScopes(@NotNull String scopes) {
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN, "basetool-ingest", thumbprint, member, scopes, issuedAt));
  }

  /**
   * Sends one DPoP-bound request with a valid nonce.
   *
   * @param method the method
   * @param path the path
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions call(@NotNull HttpMethod method, @NotNull String path)
      throws Exception {
    String nonce =
        mockMvc
            .perform(
                request(method, path)
                    .header(HttpHeaders.AUTHORIZATION, "DPoP " + TOKEN)
                    .header(
                        "DPoP", ExchangeTestSupport.proof(key, TOKEN, method.name(), path, null)))
            .andReturn()
            .getResponse()
            .getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER);
    return mockMvc.perform(
        request(method, path)
            .header(HttpHeaders.AUTHORIZATION, "DPoP " + TOKEN)
            .header(HttpHeaders.USER_AGENT, USER_AGENT)
            .header("DPoP", ExchangeTestSupport.proof(key, TOKEN, method.name(), path, nonce)));
  }

  /**
   * Reads the exchange refusal counter.
   *
   * @param reason the reason
   * @return the count
   */
  private double refused(@NotNull String reason) {
    return meterRegistry
        .get(MetricNames.EXCHANGE_REFUSED)
        .tag(MetricNames.TAG_REASON, reason)
        .counter()
        .count();
  }
}
