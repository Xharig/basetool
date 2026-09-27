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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link IngestPathScope}, the scope decision shared by the client-identity,
 * payload, rate-limit and access-log filters, which is made on the decoded path that Spring MVC
 * routes on.
 */
class IngestPathScopeTest {

  /**
   * Builds a request whose raw URI is exactly the given string.
   *
   * @param uri the raw, possibly percent-encoded request URI
   * @return a mock POST request carrying that URI
   */
  private static MockHttpServletRequest request(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
    request.setRequestURI(uri);
    return request;
  }

  @Test
  void matchesAPlainIngestPath() {
    assertThat(IngestPathScope.isProtectedRequest(request("/v1/refinery-extract"))).isTrue();
  }

  @Test
  void matchesAPercentEncodedIngestPath() {
    assertThat(IngestPathScope.isProtectedRequest(request("/%761/refinery-extract"))).isTrue();
    assertThat(IngestPathScope.isProtectedRequest(request("/v%31/refinery-extract"))).isTrue();
  }

  @Test
  void doesNotMatchTheUnauthenticatedOperationalEndpoints() {
    assertThat(IngestPathScope.isProtectedRequest(request("/actuator/health"))).isFalse();
    assertThat(IngestPathScope.isProtectedRequest(request("/v3/api-docs"))).isFalse();
  }

  @Test
  void doesNotMatchAPathThatMerelyStartsWithTheScopeLiteral() {
    assertThat(IngestPathScope.isProtectedRequest(request("/v1x/refinery-extract"))).isFalse();
  }

  @Test
  void theExchangeIsProtectedButOutsideTheLegacyClientGate() {
    MockHttpServletRequest exchange = request("/exchange/v1/catalog/resolve");

    assertThat(IngestPathScope.isProtectedRequest(exchange)).isTrue();
    assertThat(IngestPathScope.isExchangeRequest(exchange)).isTrue();
    assertThat(IngestPathScope.isLegacyRequest(exchange)).isFalse();
  }

  @Test
  void theLegacySurfaceIsProtectedAndCarriesTheClientGate() {
    MockHttpServletRequest legacy = request("/v1/blueprint-preview");

    assertThat(IngestPathScope.isLegacyRequest(legacy)).isTrue();
    assertThat(IngestPathScope.isExchangeRequest(legacy)).isFalse();
  }

  @Test
  void labelsEachSurfaceForTheMetrics() {
    assertThat(IngestPathScope.scopeLabel(request("/v1/refinery-extract"))).isEqualTo("legacy");
    assertThat(IngestPathScope.scopeLabel(request("/exchange/v1"))).isEqualTo("exchange");
    assertThat(IngestPathScope.scopeLabel(request("/actuator/health"))).isEqualTo("other");
  }

  @Test
  void doesNotMatchAPathThatMerelyStartsWithTheExchangeLiteral() {
    assertThat(IngestPathScope.isProtectedRequest(request("/exchangex/v1"))).isFalse();
  }
}
