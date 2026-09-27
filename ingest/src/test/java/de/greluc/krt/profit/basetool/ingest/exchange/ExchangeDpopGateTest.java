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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.service.BackendImportClient;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/** The exchange token gate end to end, with real DPoP proofs (REQ-XCH-004, REQ-XCH-006). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ExchangeDpopGateTest.ProbeRoute.class)
class ExchangeDpopGateTest {

  private static final String PROBE = "/exchange/v1/test-probe";
  private static final String HTU = "http://localhost" + PROBE;
  private static final String TOKEN = "exchange-token";
  private static final String UNBOUND_TOKEN = "unbound-token";
  private static final String FOREIGN_TOKEN = "foreign-audience-token";

  @Autowired private WebApplicationContext context;
  @Autowired private MeterRegistry meterRegistry;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private BackendImportClient backendImportClient;
  @MockitoBean private HandoffStagingService handoffStagingService;

  private MockMvc mockMvc;
  private ECKey key;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    key = new ECKeyGenerator(Curve.P_256).generate();
    String thumbprint = key.computeThumbprint().toString();
    when(jwtDecoder.decode(TOKEN)).thenReturn(token(TOKEN, "basetool-ingest", thumbprint));
    when(jwtDecoder.decode(UNBOUND_TOKEN))
        .thenReturn(token(UNBOUND_TOKEN, "basetool-ingest", null));
    when(jwtDecoder.decode(FOREIGN_TOKEN))
        .thenReturn(token(FOREIGN_TOKEN, "basetool-backend", thumbprint));
  }

  @Test
  void aBearerTokenIsRefusedAsDpopRequired() throws Exception {
    double before = refused("dpop_required");

    mockMvc
        .perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("DPoP algs=")))
        .andExpect(jsonPath("$.code").value("DPOP_REQUIRED"));

    assertThat(refused("dpop_required") - before).isEqualTo(1.0d);
  }

  @Test
  void aProofWithoutTheNonceGetsTheNonceAndTheRetryPasses() throws Exception {
    MvcResult challenge =
        mockMvc
            .perform(dpop(TOKEN, proof(key, TOKEN, null)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("DPOP_INVALID"))
            .andExpect(
                header()
                    .string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        org.hamcrest.Matchers.containsString("error=\"use_dpop_nonce\"")))
            .andReturn();
    String nonce = challenge.getResponse().getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER);
    assertThat(nonce).isNotBlank();

    mockMvc
        .perform(dpop(TOKEN, proof(key, TOKEN, nonce)))
        .andExpect(status().isOk())
        .andExpect(header().exists(ExchangeTokenGateFilter.DPOP_NONCE_HEADER));
    assertThat(authFailures("use_dpop_nonce")).isGreaterThanOrEqualTo(1.0d);
  }

  @Test
  void aReplayedProofIsRefused() throws Exception {
    String proof = proof(key, TOKEN, nonce());

    mockMvc.perform(dpop(TOKEN, proof)).andExpect(status().isOk());
    mockMvc
        .perform(dpop(TOKEN, proof))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DPOP_INVALID"));
  }

  @Test
  void aProofByAnotherKeyIsRefused() throws Exception {
    ECKey other = new ECKeyGenerator(Curve.P_256).generate();

    mockMvc
        .perform(dpop(TOKEN, proof(other, TOKEN, nonce())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DPOP_INVALID"));
  }

  @Test
  void anUnboundTokenIsRefusedAsDpopRequired() throws Exception {
    mockMvc
        .perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + UNBOUND_TOKEN))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DPOP_REQUIRED"));
  }

  @Test
  void aTokenForAnotherAudienceIsRefusedWithTheAudiencePropertyBlank() throws Exception {
    double before = refused("unauthenticated");

    mockMvc
        .perform(dpop(FOREIGN_TOKEN, proof(key, FOREIGN_TOKEN, nonce())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

    assertThat(refused("unauthenticated") - before).isEqualTo(1.0d);
  }

  @Test
  void anAnonymousExchangeRequestIsChallengedForDpop() throws Exception {
    mockMvc
        .perform(get(PROBE))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("DPoP")))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void theContractDocumentsStayAnonymous() throws Exception {
    mockMvc
        .perform(get("/exchange/v1/openapi.json"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(ExchangeTokenGateFilter.DPOP_NONCE_HEADER));
  }

  @Test
  void theNonceIsRequiredOnExchangeRoutesOnly() {
    assertThat(
            ExchangeDpopProofValidation.isExchange(
                org.springframework.security.oauth2.jwt.DPoPProofContext.withDPoPProof("x")
                    .method("POST")
                    .targetUri("https://ingest.example/v1/refinery-extract")
                    .build()))
        .isFalse();
    assertThat(
            ExchangeDpopProofValidation.isExchange(
                org.springframework.security.oauth2.jwt.DPoPProofContext.withDPoPProof("x")
                    .method("POST")
                    .targetUri("https://ingest.example/exchange/v1/catalog/resolve")
                    .build()))
        .isTrue();
  }

  /**
   * Fetches a valid nonce through a challenge.
   *
   * @return the nonce
   * @throws Exception if the request fails
   */
  private @NotNull String nonce() throws Exception {
    return mockMvc
        .perform(dpop(TOKEN, proof(key, TOKEN, null)))
        .andReturn()
        .getResponse()
        .getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER);
  }

  /**
   * Builds a DPoP-scheme request to the probe.
   *
   * @param token the access token
   * @param proof the proof
   * @return the request
   */
  private static @NotNull MockHttpServletRequestBuilder dpop(
      @NotNull String token, @NotNull String proof) {
    return get(PROBE).header(HttpHeaders.AUTHORIZATION, "DPoP " + token).header("DPoP", proof);
  }

  /**
   * Signs a proof for a GET of the probe.
   *
   * @param signer the key that signs the proof
   * @param token the access token the proof binds
   * @param nonce the server nonce, or {@code null} for none
   * @return the compact proof
   * @throws Exception if signing fails
   */
  private static @NotNull String proof(
      @NotNull ECKey signer, @NotNull String token, @Nullable String nonce) throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .claim("htm", "GET")
            .claim("htu", HTU)
            .issueTime(Date.from(Instant.now()))
            .jwtID(UUID.randomUUID().toString())
            .claim("ath", Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
    if (nonce != null) {
      claims.claim("nonce", nonce);
    }
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt"))
                .jwk(signer.toPublicJWK())
                .build(),
            claims.build());
    jwt.sign(new ECDSASigner(signer));
    return jwt.serialize();
  }

  /**
   * Builds a decoded access token.
   *
   * @param value the token value
   * @param audience the audience
   * @param thumbprint the bound key's thumbprint, or {@code null} for an unbound token
   * @return the token
   */
  private static @NotNull Jwt token(
      @NotNull String value, @NotNull String audience, @Nullable String thumbprint) {
    Jwt.Builder builder =
        Jwt.withTokenValue(value)
            .header("alg", "ES256")
            .subject(UUID.randomUUID().toString())
            .audience(List.of(audience))
            .claim("azp", "versekit")
            .claim("scope", "exchange.connect")
            .issuedAt(Instant.now().minusSeconds(5))
            .expiresAt(Instant.now().plusSeconds(300));
    if (thumbprint != null) {
      builder.claim("cnf", Map.of("jkt", thumbprint));
    }
    return builder.build();
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

  /**
   * Reads the exchange share of the auth-failure counter.
   *
   * @param reason the reason
   * @return the count, zero when never counted
   */
  private double authFailures(@NotNull String reason) {
    var counter =
        meterRegistry
            .find(MetricNames.INGEST_AUTH_FAILURES)
            .tag(MetricNames.TAG_REASON, reason)
            .tag(MetricNames.TAG_PATH_SCOPE, MetricNames.PATH_SCOPE_EXCHANGE)
            .counter();
    return counter == null ? 0.0d : counter.count();
  }

  /** A test-only exchange route, registered as a function so no other test context sees it. */
  @TestConfiguration
  static class ProbeRoute {

    /**
     * Answers the probe with {@code 200}.
     *
     * @return the route
     */
    @Bean
    RouterFunction<ServerResponse> exchangeProbe() {
      return RouterFunctions.route().GET(PROBE, request -> ServerResponse.ok().body("ok")).build();
    }
  }
}
