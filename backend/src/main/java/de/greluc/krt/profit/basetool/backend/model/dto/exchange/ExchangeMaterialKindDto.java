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

import org.jetbrains.annotations.NotNull;

/**
 * A material's read-only classification, which a client uses to route a lot to its own lists
 * (REQ-XCH-016).
 *
 * @param type {@code RAW}, {@code REFINED} or {@code NO_REFINE}
 * @param commodity whether the material is a UEX commodity
 */
public record ExchangeMaterialKindDto(@NotNull String type, boolean commodity) {}
