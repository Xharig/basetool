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

import java.util.UUID;

/**
 * The game identifiers of one active blueprint and its output item, for resolving a blueprint by
 * GUID or UEX id (REQ-XCH-012).
 *
 * @param outputName the blueprint's output name, from which its product key is derived
 * @param blueprintScwikiUuid the blueprint's Wiki UUID, or {@code null}
 * @param blueprintP4kUuid the blueprint's game-file UUID, or {@code null}
 * @param itemExternalUuid the output item's Wiki UUID, or {@code null}
 * @param itemP4kUuid the output item's game-file UUID, or {@code null}
 * @param itemUexId the output item's UEX id, or {@code null}
 */
public record ExchangeBlueprintKeyRow(
    String outputName,
    UUID blueprintScwikiUuid,
    UUID blueprintP4kUuid,
    UUID itemExternalUuid,
    UUID itemP4kUuid,
    Integer itemUexId) {}
