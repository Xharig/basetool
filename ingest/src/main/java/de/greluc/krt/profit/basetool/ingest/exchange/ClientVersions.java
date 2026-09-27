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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Compares a client's {@code User-Agent} version with the registry's minimum (REQ-XCH-024). The
 * gate is cooperative: it stops honest old releases, not a client that lies.
 */
public final class ClientVersions {

  /** {@code <Product>/<major.minor.patch>[-pre|+build] [(+url)]}. */
  private static final Pattern USER_AGENT =
      Pattern.compile(
          "^[A-Za-z0-9._-]{1,64}/(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})"
              + "([-+][0-9A-Za-z.+-]*)?(\\s.*)?$");

  /** {@code major.minor.patch}. */
  private static final Pattern VERSION = Pattern.compile("^(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})$");

  /** Not instantiable. */
  private ClientVersions() {}

  /**
   * Whether a request's {@code User-Agent} meets the minimum.
   *
   * @param userAgent the header, or {@code null}
   * @param minimum the registry's minimum as {@code major.minor.patch}, or {@code null} for none
   * @return {@code true} when there is no minimum, or the agent names a version at or above it; a
   *     pre-release of the minimum itself is below it
   */
  public static boolean meets(@Nullable String userAgent, @Nullable String minimum) {
    if (minimum == null || minimum.isBlank()) {
      return true;
    }
    Matcher min = VERSION.matcher(minimum.strip());
    if (!min.matches() || userAgent == null) {
      return false;
    }
    Matcher agent = USER_AGENT.matcher(userAgent.strip());
    if (!agent.matches()) {
      return false;
    }
    for (int group = 1; group <= 3; group++) {
      int compared =
          Long.compare(Long.parseLong(agent.group(group)), Long.parseLong(min.group(group)));
      if (compared != 0) {
        return compared > 0;
      }
    }
    String suffix = agent.group(4);
    return suffix == null || suffix.startsWith("+");
  }
}
