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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.service.BackendImportClient;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class LegacyEndpointGoneFilterTest {

  /** The switch is off: every legacy request is gone, before any authentication. */
  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
  @TestPropertySource(properties = "app.ingest.legacy-endpoints.enabled=false")
  class SwitchedOff {

    @Autowired private WebApplicationContext context;
    @Autowired private MeterRegistry meterRegistry;

    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private BackendImportClient backendImportClient;
    @MockitoBean private HandoffStagingService handoffStagingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
      mockMvc =
          MockMvcBuilders.webAppContextSetup(context)
              .addFilters(context.getBean(LegacyEndpointGoneFilter.class))
              .apply(springSecurity())
              .build();
    }

    @Test
    void bothLegacyEndpointsAnswerGoneWithTheGermanHint() throws Exception {
      double before = gone();
      for (String path : new String[] {"/v1/refinery-extract", "/v1/blueprint-preview"}) {
        mockMvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isGone())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("LEGACY_ENDPOINT_GONE"))
            .andExpect(jsonPath("$.detail").value(LegacyEndpointGoneFilter.DETAIL));
      }

      assertThat(gone() - before).isEqualTo(2.0d);
      assertThat(meterRegistry.get(MetricNames.INGEST_LEGACY_ENABLED).gauge().value()).isZero();
    }

    @Test
    void theExchangeIsNotTouched() throws Exception {
      mockMvc.perform(get("/exchange/v1/openapi.json")).andExpect(status().isOk());
    }

    /**
     * Reads the refusal counter.
     *
     * @return its count
     */
    private double gone() {
      return meterRegistry.get(MetricNames.INGEST_LEGACY_GONE).counter().count();
    }
  }

  /** The default: the legacy endpoints behave exactly as before. */
  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
  class SwitchedOn {

    @Autowired private WebApplicationContext context;
    @Autowired private MeterRegistry meterRegistry;

    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private BackendImportClient backendImportClient;
    @MockitoBean private HandoffStagingService handoffStagingService;

    @Test
    void aLegacyRequestStillReachesTheSecurityChain() throws Exception {
      MockMvc mockMvc =
          MockMvcBuilders.webAppContextSetup(context)
              .addFilters(context.getBean(LegacyEndpointGoneFilter.class))
              .apply(springSecurity())
              .build();

      mockMvc
          .perform(
              post("/v1/refinery-extract").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isUnauthorized());
      assertThat(meterRegistry.get(MetricNames.INGEST_LEGACY_ENABLED).gauge().value())
          .isEqualTo(1.0d);
    }
  }
}
