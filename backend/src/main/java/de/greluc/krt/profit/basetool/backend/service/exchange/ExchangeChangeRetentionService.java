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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.model.ExchangeFeedHorizon;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeChangeRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeFeedHorizonRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purges change-feed entries past their retention and moves the feed's horizon, below which a
 * cursor has expired (REQ-XCH-013).
 */
@Service
@RequiredArgsConstructor
public class ExchangeChangeRetentionService {

  private final ExchangeChangeRepository changeRepository;
  private final ExchangeFeedHorizonRepository horizonRepository;

  /**
   * Deletes every entry up to the last one older than the cutoff and records that sequence number
   * as the horizon; the horizon never moves back.
   *
   * @param cutoff the oldest change still kept
   * @param now the time recorded with the horizon
   * @return the number of entries deleted
   */
  @Transactional
  public int purgeOlderThan(@NotNull Instant cutoff, @NotNull Instant now) {
    long through = changeRepository.maxSeqBefore(cutoff);
    if (through == 0) {
      return 0;
    }
    int deleted = changeRepository.deleteThrough(through);
    ExchangeFeedHorizon horizon =
        horizonRepository
            .findById(ExchangeFeedHorizon.SINGLETON_ID)
            .orElseThrow(() -> new IllegalStateException("exchange_feed_horizon row missing"));
    if (through > horizon.getPurgedThroughSeq()) {
      horizon.setPurgedThroughSeq(through);
      horizon.setPurgedAt(now);
      horizonRepository.save(horizon);
    }
    return deleted;
  }

  /**
   * Returns the sequence number below which the feed has lost entries.
   *
   * @return the horizon, {@code 0} before the first purge
   */
  @Transactional(readOnly = true)
  public long horizon() {
    return horizonRepository
        .findById(ExchangeFeedHorizon.SINGLETON_ID)
        .map(ExchangeFeedHorizon::getPurgedThroughSeq)
        .orElse(0L);
  }
}
