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

// @ts-check

(function () {
    const BASE = '/connected-apps';

    const host = document.getElementById('ca-host');
    if (!host) {
        return;
    }
    const i18n = readMessages();

    function readMessages() {
        const holder = document.getElementById('ca-i18n');
        const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
        /** @param {string} key */
        const text = function (key) {
            const attribute = 'data-' + key.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
            return window.krtI18nText(data[key], attribute);
        };
        return {
            disconnected: text('disconnected'),
            error: text('error'),
            confirmOk: text('confirmOk'),
            confirmCancel: text('confirmCancel'),
            confirmClientTitle: text('confirmClientTitle'),
            confirmClientBody: text('confirmClientBody'),
            confirmInstallationTitle: text('confirmInstallationTitle'),
            confirmInstallationBody: text('confirmInstallationBody'),
        };
    }

    /** Re-renders the list in place from the `apps` fragment. */
    function refreshApps() {
        return window.krtFetch.swap({
            url: BASE,
            container: '#ca-host',
            fragmentValue: 'apps',
            errorMessage: i18n.error,
        });
    }

    /**
     * Asks first, then sends one disconnect and re-renders the list.
     * @param {string} titleText
     * @param {string} bodyText
     * @param {string} url
     * @param {Element} submitter
     */
    function disconnect(titleText, bodyText, url, submitter) {
        if (!window.krtFetch) {
            return;
        }
        const send = function () {
            window.krtFetch.write({
                method: 'DELETE',
                url,
                successMessage: i18n.disconnected,
                errorMessage: i18n.error,
                submitter,
                onSuccess() {
                    return refreshApps();
                },
            });
        };
        if (typeof window.showKrtConfirm !== 'function') {
            send();
            return;
        }
        window
            .showKrtConfirm(titleText, bodyText, i18n.confirmOk, i18n.confirmCancel)
            .then((ok) => {
                if (ok) {
                    send();
                }
            });
    }

    host.addEventListener('click', function (event) {
        const target = event.target;
        if (!(target instanceof Element)) {
            return;
        }
        const clientBtn = target.closest('[data-ca-disconnect-client]');
        if (clientBtn) {
            const app = clientBtn.closest('[data-client-id]');
            const clientId = app ? app.getAttribute('data-client-id') : null;
            if (clientId) {
                disconnect(
                    i18n.confirmClientTitle,
                    i18n.confirmClientBody,
                    BASE + '/' + encodeURIComponent(clientId),
                    clientBtn,
                );
            }
            return;
        }
        const installationBtn = target.closest('[data-ca-disconnect-installation]');
        if (installationBtn) {
            const row = installationBtn.closest('[data-installation-id]');
            const id = row ? row.getAttribute('data-installation-id') : null;
            if (id) {
                disconnect(
                    i18n.confirmInstallationTitle,
                    i18n.confirmInstallationBody,
                    BASE + '/installations/' + encodeURIComponent(id),
                    installationBtn,
                );
            }
        }
    });
})();
