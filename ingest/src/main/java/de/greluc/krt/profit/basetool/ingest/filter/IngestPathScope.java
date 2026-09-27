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

import jakarta.servlet.http.HttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Decides which gateway surface a request targets, shared by every scoped filter: the legacy
 * extractor endpoints ({@code /v1/**}) and the exchange ({@code /exchange/**}) together form the
 * protected surface; only the legacy one carries the extractor client gate (REQ-XCH-001).
 *
 * <p>Matches the decoded path, as the dispatcher does, so a percent-encoded path cannot bypass the
 * filters while still reaching a controller.
 */
final class IngestPathScope {

  /** The parsed {@code /v1/**} pattern of the legacy extractor surface. */
  private static final PathPattern LEGACY_PATHS = PathPatternParser.defaultInstance.parse("/v1/**");

  /** The parsed {@code /exchange/**} pattern of the exchange surface. */
  private static final PathPattern EXCHANGE_PATHS =
      PathPatternParser.defaultInstance.parse("/exchange/**");

  /** Not instantiable: this is a single shared predicate, not a collaborator. */
  private IngestPathScope() {}

  /**
   * Whether the request targets the protected surface — the per-IP limit, the payload cap and the
   * access log apply.
   *
   * @param request the current request
   * @return {@code true} when the path is under {@code /v1} or {@code /exchange}
   */
  static boolean isProtectedRequest(@NotNull HttpServletRequest request) {
    return isLegacyRequest(request) || isExchangeRequest(request);
  }

  /**
   * Whether the request targets a legacy extractor endpoint — the extractor client gate applies.
   *
   * @param request the current request
   * @return {@code true} when the path is under {@code /v1}
   */
  static boolean isLegacyRequest(@NotNull HttpServletRequest request) {
    return LEGACY_PATHS.matches(PathContainer.parsePath(request.getRequestURI()));
  }

  /**
   * Whether the request targets the exchange surface.
   *
   * @param request the current request
   * @return {@code true} when the path is under {@code /exchange}
   */
  static boolean isExchangeRequest(@NotNull HttpServletRequest request) {
    return EXCHANGE_PATHS.matches(PathContainer.parsePath(request.getRequestURI()));
  }
}
