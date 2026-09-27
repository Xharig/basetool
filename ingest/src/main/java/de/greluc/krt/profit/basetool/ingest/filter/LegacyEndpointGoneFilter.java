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

import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.web.ProblemResponseWriter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers every legacy extractor request under {@code /v1} with {@code 410 LEGACY_ENDPOINT_GONE}
 * once {@code app.ingest.legacy-endpoints.enabled} is {@code false} (REQ-XCH-033).
 *
 * <p>Runs before the security chain, so an outdated extractor sees the update hint whether or not
 * its token is still accepted.
 */
@Component
@Order(LegacyEndpointGoneFilter.ORDER)
@RequiredArgsConstructor
public class LegacyEndpointGoneFilter extends OncePerRequestFilter {

  /** Just inside the access log, so the refusals are logged. */
  public static final int ORDER = RequestLoggingFilter.ORDER + 1;

  /** The stable problem code of the refusal. */
  public static final String CODE = "LEGACY_ENDPOINT_GONE";

  /** The German hint the shipped extractor shows verbatim. */
  static final String DETAIL =
      "Diese Schnittstelle wurde abgeschaltet. Bitte aktualisiere den SC Extractor auf die neueste"
          + " Version.";

  private final IngestProperties ingestProperties;
  private final ObjectMapper objectMapper;
  private final LoggingProperties loggingProperties;
  private final MeterRegistry meterRegistry;

  /** Registers the refusal counter at zero and the switch's state as a gauge. */
  @PostConstruct
  void registerMeters() {
    meterRegistry.counter(MetricNames.INGEST_LEGACY_GONE);
    double enabled = ingestProperties.legacyEndpoints().enabled() ? 1.0d : 0.0d;
    Gauge.builder(MetricNames.INGEST_LEGACY_ENABLED, () -> enabled)
        .description("1 while the legacy extractor endpoints answer, 0 once they are switched off.")
        .register(meterRegistry);
  }

  /**
   * Refuses the request with {@code 410} and counts it.
   *
   * @param request the legacy request
   * @param response the response to write the problem into
   * @param filterChain not continued
   * @throws IOException if the problem cannot be written
   */
  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws IOException {
    meterRegistry.counter(MetricNames.INGEST_LEGACY_GONE).increment();
    ProblemResponseWriter.write(
        response, objectMapper, loggingProperties, HttpStatus.GONE, "Gone", CODE, DETAIL);
  }

  /**
   * Leaves everything alone while the legacy endpoints are on, and every path outside {@code /v1}.
   *
   * @param request the current request
   * @return {@code true} to bypass the filter
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return ingestProperties.legacyEndpoints().enabled()
        || !IngestPathScope.isLegacyRequest(request);
  }
}
