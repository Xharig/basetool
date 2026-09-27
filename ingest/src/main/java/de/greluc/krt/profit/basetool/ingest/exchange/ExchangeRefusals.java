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

import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Counts the gateway's exchange refusals by their problem code in snake case (REQ-XCH-025,
 * REQ-XCH-028).
 */
@Component
@RequiredArgsConstructor
public class ExchangeRefusals {

  /** A request without a DPoP proof or with an unbound token. */
  public static final String DPOP_REQUIRED = "DPOP_REQUIRED";

  /** An invalid, replayed or foreign proof, or one without the server nonce. */
  public static final String DPOP_INVALID = "DPOP_INVALID";

  /** A token that is missing, invalid, or not issued for this gateway. */
  public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

  /** Every code this counter knows, registered at zero. */
  static final @Unmodifiable List<String> CODES =
      List.of(DPOP_REQUIRED, DPOP_INVALID, UNAUTHENTICATED);

  private final MeterRegistry meterRegistry;

  /** Registers every reason at zero, so a first refusal is an increase. */
  @PostConstruct
  void register() {
    CODES.forEach(
        code ->
            meterRegistry.counter(
                MetricNames.EXCHANGE_REFUSED, MetricNames.TAG_REASON, reason(code)));
  }

  /**
   * Counts one refusal.
   *
   * @param code the problem code
   */
  public void count(@NotNull String code) {
    meterRegistry
        .counter(MetricNames.EXCHANGE_REFUSED, MetricNames.TAG_REASON, reason(code))
        .increment();
  }

  /**
   * Returns the metric reason of a problem code.
   *
   * @param code the problem code
   * @return the code in lower case
   */
  static @NotNull String reason(@NotNull String code) {
    return code.toLowerCase(Locale.ROOT);
  }
}
