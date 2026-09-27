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

package de.greluc.krt.profit.basetool.backend.model.dto.exchange;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One of the member's blueprints in the exchange feed (REQ-XCH-015); absent optional fields are
 * left out.
 *
 * @param key the opaque key the feed and its tombstones use for the product
 * @param ref the product, with the Basetool key {@code bt} and its display name
 * @param acquiredAt when the member acquired it in the game, or {@code null}
 * @param isDefault whether it is granted to every member and so cannot be removed
 * @param note the member's note, read-only in v1, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeBlueprintDto(
    @NotNull String key,
    @NotNull ExchangeItemRefDto ref,
    @Nullable Instant acquiredAt,
    boolean isDefault,
    @Nullable String note) {}
