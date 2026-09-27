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
 * One of a member's ships as the exchange reads it in one query, with its type and location
 * (REQ-XCH-017).
 *
 * @param id the ship's id
 * @param version the ship's optimistic-lock version, or {@code null} before its first write
 * @param name the member's name for it, or {@code null}
 * @param shipTypeId the ship type's id
 * @param shipTypeName the ship type's name
 * @param insurance {@code LTI} or the insurance months as digits, or {@code null}
 * @param locationName the location's name, or {@code null} when the ship has none
 * @param uexCityId the UEX id of the location's city, or {@code null}
 * @param uexSpaceStationId the UEX id of the location's space station, or {@code null}
 * @param fitted whether the ship is fitted
 */
public record ExchangeShipRow(
    UUID id,
    Long version,
    String name,
    UUID shipTypeId,
    String shipTypeName,
    String insurance,
    String locationName,
    Integer uexCityId,
    Integer uexSpaceStationId,
    boolean fitted) {}
