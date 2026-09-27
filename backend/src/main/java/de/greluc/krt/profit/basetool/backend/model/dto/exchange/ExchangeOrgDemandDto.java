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

import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The anonymised open demand of the units the member belongs to: no requester, assignee, title,
 * free text or per-order breakdown (REQ-XCH-018).
 *
 * @param materials the open material lines
 * @param items the open item lines of item orders
 * @param updatedAt when the demand was computed
 */
public record ExchangeOrgDemandDto(
    @NotNull @Unmodifiable List<ExchangeDemandMaterialDto> materials,
    @NotNull @Unmodifiable List<ExchangeDemandItemDto> items,
    @NotNull Instant updatedAt) {}
