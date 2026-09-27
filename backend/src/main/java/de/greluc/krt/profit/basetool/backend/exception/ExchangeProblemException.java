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

package de.greluc.krt.profit.basetool.backend.exception;

import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * A refusal of the exchange layer carrying a code of the exchange error registry, which the ingest
 * gateway passes on to the client unchanged (REQ-XCH-011).
 *
 * <p>The status and code are per instance; the title and detail come from {@code
 * problem.<code>.title} and {@code .detail}.
 */
public final class ExchangeProblemException extends AppException {

  /** A feed cursor older than the retained changes, or not one the server issued. */
  public static final String CURSOR_EXPIRED = "CURSOR_EXPIRED";

  /** A change set that removes more than the mass-change guard allows without confirmation. */
  public static final String MASS_CHANGE_CONFIRMATION_REQUIRED =
      "MASS_CHANGE_CONFIRMATION_REQUIRED";

  private final HttpStatus status;
  private final String code;

  /**
   * Creates the refusal.
   *
   * @param status the HTTP status
   * @param code the exchange error code
   * @param message the log message, never shown to the client
   */
  public ExchangeProblemException(
      @NotNull HttpStatus status, @NotNull String code, @NotNull String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /**
   * The refusal of a feed cursor the server can no longer serve.
   *
   * @return the {@code 410 CURSOR_EXPIRED} refusal
   */
  public static @NotNull ExchangeProblemException cursorExpired() {
    return new ExchangeProblemException(
        HttpStatus.GONE, CURSOR_EXPIRED, "The feed cursor is older than the retained changes.");
  }

  /**
   * The refusal of a change set the member must confirm in the browser; nothing was written.
   *
   * @return the {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} refusal
   */
  public static @NotNull ExchangeProblemException massChangeConfirmationRequired() {
    return new ExchangeProblemException(
        HttpStatus.CONFLICT,
        MASS_CHANGE_CONFIRMATION_REQUIRED,
        "The change set removes more than the mass-change guard allows.");
  }

  @Override
  public HttpStatus status() {
    return status;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public String typeSuffix() {
    return code.toLowerCase(Locale.ROOT).replace('_', '-');
  }

  @NotNull
  @Override
  public String titleKey() {
    return "problem." + code.toLowerCase(Locale.ROOT) + ".title";
  }

  @NotNull
  @Override
  public String detailKey() {
    return "problem." + code.toLowerCase(Locale.ROOT) + ".detail";
  }

  @NotNull
  @Override
  public String logLabel() {
    return "Exchange refusal";
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<String, ?> logExtra() {
    return Map.of("exchangeCode", code);
  }
}
