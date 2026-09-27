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

package de.greluc.krt.profit.basetool.ingest.filter;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeRoutes;
import de.greluc.krt.profit.basetool.ingest.service.BackendImportClient;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import de.greluc.krt.profit.basetool.testsupport.web.Call;
import de.greluc.krt.profit.basetool.testsupport.web.EndpointEnumeration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the gateway's routed surface to exactly its two ingest endpoints (REQ-INGEST-001), since the
 * protective filters scope themselves to {@code /v1/**} via {@link IngestPathScope}. Uses {@link
 * EndpointEnumeration}; only springdoc's {@code /v3/api-docs} tree and Boot's {@code /error} are
 * tolerated besides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class IngestEndpointSurfaceTest {

  /** The complete routed ingest surface (REQ-INGEST-001). */
  private static final Set<Call> INGEST_SURFACE =
      Set.of(
          new Call(HttpMethod.POST, "/v1/refinery-extract"),
          new Call(HttpMethod.POST, "/v1/blueprint-preview"),
          new Call(HttpMethod.GET, "/exchange/v1/openapi.json"),
          new Call(HttpMethod.GET, "/exchange/v1/schemas/x"),
          new Call(HttpMethod.GET, "/exchange/v1"),
          new Call(HttpMethod.POST, "/exchange/v1/me/installation"),
          new Call(HttpMethod.POST, "/exchange/v1/catalog/resolve"),
          new Call(HttpMethod.GET, "/exchange/v1/catalog/locations"));

  /** springdoc's OpenAPI document tree, served in non-prod profiles only. */
  private static final String API_DOCS_ROOT = "/v3/api-docs";

  /**
   * springdoc's YAML rendering of the same document, a sibling of the tree rather than under it.
   */
  private static final String API_DOCS_YAML = API_DOCS_ROOT + ".yaml";

  /**
   * Boot's {@code BasicErrorController}: the target of the container's error dispatch, not an
   * application endpoint. Whether the dispatcher lists it depends on which auto-configurations the
   * shared test context happened to start first, so it is tolerated rather than required.
   */
  private static final String ERROR_PATH = "/error";

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private BackendImportClient backendImportClient;
  @MockitoBean private HandoffStagingService handoffStagingService;

  @Test
  void theDispatcherRoutesExactlyTheGatewaySurface() {
    List<Call> routed =
        EndpointEnumeration.mappings(context).stream()
            .filter(call -> !EndpointEnumeration.isUnder(call.path(), API_DOCS_ROOT))
            .filter(call -> !call.path().equals(API_DOCS_YAML))
            .filter(call -> !call.path().equals(ERROR_PATH))
            .toList();

    assertThat(routed)
        .as(
            "every endpoint outside /v1 and /exchange is served WITHOUT the payload cap, rate"
                + " limit and access log — add it there or extend IngestPathScope first")
        .containsExactlyInAnyOrderElementsOf(INGEST_SURFACE);
  }

  @Test
  void everyRoutedIngestEndpointIsInsideTheProtectiveFilterScope() {
    for (Call call : INGEST_SURFACE) {
      MockHttpServletRequest request =
          new MockHttpServletRequest(call.method().name(), call.path());
      request.setRequestURI(call.path());

      assertThat(IngestPathScope.isProtectedRequest(request))
          .as("%s must be covered by the protective filters", call)
          .isTrue();
    }
  }

  @Test
  void everyServedExchangeRouteIsInTheGatedRouteTable() {
    for (Call call : EndpointEnumeration.mappings(context)) {
      if (!EndpointEnumeration.isUnder(call.path(), "/exchange")
          || call.path().equals("/exchange/v1/openapi.json")
          || call.path().startsWith("/exchange/v1/schemas/")) {
        continue;
      }
      assertThat(ExchangeRoutes.find(call.method().name(), call.path()))
          .as("%s is served but not in ExchangeRoutes, so the gate would answer 404", call)
          .isPresent();
    }
  }

  @Test
  void theEnumerationIsNotVacuous() {
    assertThat(EndpointEnumeration.patterns(context, HttpMethod.POST))
        .contains("/v1/refinery-extract", "/v1/blueprint-preview");
    assertThat(EndpointEnumeration.patterns(context, HttpMethod.GET))
        .contains("/exchange/v1/openapi.json", "/exchange/v1/schemas/{name}");
  }
}
