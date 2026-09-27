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

package de.greluc.krt.profit.basetool.ingest.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The exchange's default limits (REQ-XCH-023, owner decision 2026-09-27); a registry client's
 * {@code requestsPerMinute} and {@code writesPerDay} override the per-member ones.
 *
 * @param memberPerMinute requests per minute per client and member
 * @param clientPerMinute requests per minute per client, over all its members
 * @param writesPerDay write requests per UTC day per client and member
 * @param accountChecksPerHour account checks per hour per client and member
 * @param trackedBuckets the most in-process buckets kept, least recently used first out
 */
@Validated
@ConfigurationProperties(prefix = "app.exchange.limits")
public record ExchangeLimitProperties(
    @Min(1) @DefaultValue("120") int memberPerMinute,
    @Min(1) @DefaultValue("1200") int clientPerMinute,
    @Min(1) @DefaultValue("500") int writesPerDay,
    @Min(1) @DefaultValue("10") int accountChecksPerHour,
    @Min(100) @DefaultValue("50000") int trackedBuckets) {}
