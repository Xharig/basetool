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
    const BASE = '/admin/exchange-clients';

    const form = document.getElementById('xc-form');
    const title = document.getElementById('xc-form-title');
    const version = document.getElementById('xc-version');
    const clientId = document.getElementById('xc-clientId');
    const displayName = document.getElementById('xc-displayName');
    const minVersion = document.getElementById('xc-minVersion');
    const contactUrl = document.getElementById('xc-contactUrl');
    const requestsPerMinute = document.getElementById('xc-requestsPerMinute');
    const writesPerDay = document.getElementById('xc-writesPerDay');
    if (
        !(form instanceof HTMLFormElement) ||
        !title ||
        !(version instanceof HTMLInputElement) ||
        !(clientId instanceof HTMLInputElement) ||
        !(displayName instanceof HTMLInputElement) ||
        !(minVersion instanceof HTMLInputElement) ||
        !(contactUrl instanceof HTMLInputElement) ||
        !(requestsPerMinute instanceof HTMLInputElement) ||
        !(writesPerDay instanceof HTMLInputElement)
    ) {
        return;
    }
    wire({
        form,
        title,
        version,
        clientId,
        displayName,
        minVersion,
        contactUrl,
        requestsPerMinute,
        writesPerDay,
    });

    /**
     * The registry form's controls, resolved and type-checked once.
     * @typedef {object} ClientFormElements
     * @property {HTMLFormElement} form
     * @property {HTMLElement} title the form heading, retitled between create and edit
     * @property {HTMLInputElement} version the optimistic-lock version of the client being edited
     * @property {HTMLInputElement} clientId
     * @property {HTMLInputElement} displayName
     * @property {HTMLInputElement} minVersion
     * @property {HTMLInputElement} contactUrl
     * @property {HTMLInputElement} requestsPerMinute
     * @property {HTMLInputElement} writesPerDay
     */

    /**
     * Installs the registry editor on the resolved controls.
     * @param {ClientFormElements} el
     */
    function wire(el) {
        const i18n = readMessages();

        /** The capabilities the client under edit held when it was loaded. */
        /** @type {string[]} */
        let loadedCapabilities = [];

        function readMessages() {
            const holder = document.getElementById('xc-i18n');
            const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
            /** @param {string} key */
            const text = function (key) {
                const attribute = 'data-' + key.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
                return window.krtI18nText(data[key], attribute);
            };
            return {
                saved: text('saved'),
                suspended: text('suspended'),
                activated: text('activated'),
                switched: text('switched'),
                error: text('error'),
                confirmOk: text('confirmOk'),
                confirmCancel: text('confirmCancel'),
                confirmSuspendTitle: text('confirmSuspendTitle'),
                confirmSuspendBody: text('confirmSuspendBody'),
                confirmWidenTitle: text('confirmWidenTitle'),
                confirmWidenBody: text('confirmWidenBody'),
                confirmOffTitle: text('confirmOffTitle'),
                confirmOffBody: text('confirmOffBody'),
                confirmOnTitle: text('confirmOnTitle'),
                confirmOnBody: text('confirmOnBody'),
                formCreate: text('formCreate'),
                formEdit: text('formEdit'),
            };
        }

        /** @returns {HTMLInputElement[]} */
        function capabilityBoxes() {
            return Array.from(el.form.querySelectorAll('input[name="capability"]')).filter(
                (box) => box instanceof HTMLInputElement,
            );
        }

        /** @returns {string[]} the checked capabilities, the required one always included */
        function checkedCapabilities() {
            return capabilityBoxes()
                .filter((box) => box.checked || box.dataset.required === 'true')
                .map((box) => box.value);
        }

        /**
         * @param {HTMLInputElement} input
         * @returns {string | null}
         */
        function textOrNull(input) {
            const value = input.value.trim();
            return value === '' ? null : value;
        }

        /**
         * @param {HTMLInputElement} input
         * @returns {number | null}
         */
        function numberOrNull(input) {
            const value = input.value.trim();
            return value === '' ? null : Number(value);
        }

        function buildPayload() {
            /** @type {Record<string, unknown>} */
            const payload = {
                displayName: el.displayName.value.trim(),
                capabilities: checkedCapabilities(),
                minClientVersion: textOrNull(el.minVersion),
                contactUrl: textOrNull(el.contactUrl),
                requestsPerMinute: numberOrNull(el.requestsPerMinute),
                writesPerDay: numberOrNull(el.writesPerDay),
            };
            if (editedId()) {
                payload.version = el.version.value === '' ? null : Number(el.version.value);
            } else {
                payload.clientId = el.clientId.value.trim();
            }
            return payload;
        }

        /** @returns {string} the id of the client under edit, or '' in create mode */
        function editedId() {
            return el.form.getAttribute('data-client-id') || '';
        }

        /** Puts the form back into create mode. */
        function resetForm() {
            el.form.setAttribute('data-client-id', '');
            el.form.reset();
            el.version.value = '';
            el.clientId.disabled = false;
            el.title.textContent = i18n.formCreate;
            loadedCapabilities = [];
            capabilityBoxes().forEach((box) => {
                box.checked = box.dataset.required === 'true';
            });
        }

        /** @param {ApiDto<'ExchangeClientDto'>} client */
        function prefillForm(client) {
            el.form.setAttribute('data-client-id', client.id || '');
            el.version.value = client.version != null ? String(client.version) : '';
            el.clientId.value = client.clientId || '';
            el.clientId.disabled = true;
            el.displayName.value = client.displayName || '';
            el.minVersion.value = client.minClientVersion || '';
            el.contactUrl.value = client.contactUrl || '';
            el.requestsPerMinute.value =
                client.requestsPerMinute != null ? String(client.requestsPerMinute) : '';
            el.writesPerDay.value = client.writesPerDay != null ? String(client.writesPerDay) : '';
            loadedCapabilities = Array.isArray(client.capabilities)
                ? client.capabilities.map(String)
                : [];
            capabilityBoxes().forEach((box) => {
                box.checked =
                    box.dataset.required === 'true' || loadedCapabilities.includes(box.value);
            });
            el.title.textContent = i18n.formEdit;
            el.form.scrollIntoView({ behavior: 'smooth' });
        }

        /** @param {string} id */
        function editClient(id) {
            fetch(BASE + '/' + encodeURIComponent(id), {
                headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
            })
                .then((res) => (res.ok ? res.json() : null))
                .then((client) => {
                    if (client) {
                        prefillForm(client);
                    }
                })
                .catch(() => {
                    if (typeof window.showFrontendErrorToast === 'function') {
                        window.showFrontendErrorToast(i18n.error);
                    }
                });
        }

        /** Re-renders the switch and the client table in place from the `registry` fragment. */
        function refreshRegistry() {
            return window.krtFetch.swap({
                url: BASE,
                container: '#xc-registry-host',
                fragmentValue: 'registry',
                errorMessage: i18n.error,
            });
        }

        /**
         * Runs `action` after the member confirmed, or at once where no confirm dialog exists.
         * @param {string} titleText
         * @param {string} bodyText
         * @param {() => void} action
         */
        function confirmThen(titleText, bodyText, action) {
            if (typeof window.showKrtConfirm !== 'function') {
                action();
                return;
            }
            window
                .showKrtConfirm(titleText, bodyText, i18n.confirmOk, i18n.confirmCancel)
                .then((ok) => {
                    if (ok) {
                        action();
                    }
                });
        }

        /** @param {SubmitEvent} event */
        function onSubmit(event) {
            event.preventDefault();
            if (!window.krtFetch || !el.form.reportValidity()) {
                return;
            }
            const id = editedId();
            const submitter = el.form.querySelector('button[type="submit"]');
            const save = function () {
                window.krtFetch.write({
                    method: id ? 'PUT' : 'POST',
                    url: BASE + (id ? '/' + encodeURIComponent(id) : ''),
                    payload: buildPayload(),
                    successMessage: i18n.saved,
                    errorMessage: i18n.error,
                    submitter,
                    onSuccess() {
                        resetForm();
                        return refreshRegistry();
                    },
                });
            };
            const widened = checkedCapabilities().some((cap) => !loadedCapabilities.includes(cap));
            if (id && widened) {
                confirmThen(i18n.confirmWidenTitle, i18n.confirmWidenBody, save);
            } else {
                save();
            }
        }

        /**
         * @param {Element} row the client's table row
         * @param {'suspend' | 'activate'} action
         * @param {Element} submitter
         */
        function changeStatus(row, action, submitter) {
            const id = row.getAttribute('data-client-id');
            const rowVersion = row.getAttribute('data-version');
            if (!id || !window.krtFetch) {
                return;
            }
            const send = function () {
                window.krtFetch.write({
                    method: 'POST',
                    url: BASE + '/' + encodeURIComponent(id) + '/' + action,
                    payload: { version: rowVersion == null ? null : Number(rowVersion) },
                    successMessage: action === 'suspend' ? i18n.suspended : i18n.activated,
                    errorMessage: i18n.error,
                    submitter,
                    onSuccess() {
                        return refreshRegistry();
                    },
                });
            };
            if (action === 'suspend') {
                confirmThen(i18n.confirmSuspendTitle, i18n.confirmSuspendBody, send);
            } else {
                send();
            }
        }

        /** @param {Element} submitter */
        function toggleSwitch(submitter) {
            const box = document.getElementById('xc-switch');
            if (!box || !window.krtFetch) {
                return;
            }
            const enabled = box.getAttribute('data-enabled') === 'true';
            const boxVersion = box.getAttribute('data-version');
            confirmThen(
                enabled ? i18n.confirmOffTitle : i18n.confirmOnTitle,
                enabled ? i18n.confirmOffBody : i18n.confirmOnBody,
                function () {
                    window.krtFetch.write({
                        method: 'PUT',
                        url: BASE + '/settings',
                        payload: {
                            enabled: !enabled,
                            version: boxVersion == null ? null : Number(boxVersion),
                        },
                        successMessage: i18n.switched,
                        errorMessage: i18n.error,
                        submitter,
                        onSuccess() {
                            return refreshRegistry();
                        },
                    });
                },
            );
        }

        el.form.addEventListener('submit', onSubmit);
        const cancel = document.getElementById('xc-cancel');
        if (cancel) {
            cancel.addEventListener('click', resetForm);
        }

        document.addEventListener('click', function (event) {
            const target = event.target;
            if (!(target instanceof Element)) {
                return;
            }
            const switchBtn = target.closest('[data-xc-switch]');
            if (switchBtn) {
                toggleSwitch(switchBtn);
                return;
            }
            const row = target.closest('[data-client-id]');
            if (!row || row === el.form) {
                return;
            }
            const editBtn = target.closest('[data-xc-edit]');
            if (editBtn) {
                const id = row.getAttribute('data-client-id');
                if (id) {
                    editClient(id);
                }
                return;
            }
            const suspendBtn = target.closest('[data-xc-suspend]');
            if (suspendBtn) {
                changeStatus(row, 'suspend', suspendBtn);
                return;
            }
            const activateBtn = target.closest('[data-xc-activate]');
            if (activateBtn) {
                changeStatus(row, 'activate', activateBtn);
            }
        });

        resetForm();
    }
})();
