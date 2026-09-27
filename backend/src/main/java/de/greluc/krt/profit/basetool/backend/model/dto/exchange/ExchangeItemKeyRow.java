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
 * The identifiers of one game item, for resolving an item reference (REQ-XCH-012).
 *
 * @param id the item's id
 * @param name the item's display name
 * @param className the item's DataForge class name, or {@code null}
 * @param externalUuid the item's Wiki UUID, or {@code null}
 * @param p4kUuid the item's game-file UUID, or {@code null}
 * @param uexId the item's UEX id, or {@code null}
 * @param nameKey the item's {@code global.ini} name key, or {@code null}
 */
public record ExchangeItemKeyRow(
    UUID id,
    String name,
    String className,
    UUID externalUuid,
    UUID p4kUuid,
    Integer uexId,
    String nameKey) {}
