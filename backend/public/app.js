// RakshakX admin panel — vanilla JS, no build step (loaded directly as a <script> tag).
// Talks to the same-origin API under /api/admin/*. Everything here assumes the server
// in ../server.js + ../routes/admin.js, and the element ids defined in index.html.
(() => {
  'use strict';

  const TOKEN_KEY = 'rakshakx_admin_token';
  let token = localStorage.getItem(TOKEN_KEY) || null;
  let configsCache = []; // last /api/admin/configs result, reused by the create-key modal

  // ---------------------------------------------------------------- tiny DOM helpers ----

  const $ = (id) => document.getElementById(id);
  const esc = (s) =>
    String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function show(el) { el.classList.remove('hidden'); }
  function hide(el) { el.classList.add('hidden'); }

  function toast(message, kind = 'ok') {
    const wrap = $('toasts');
    const el = document.createElement('div');
    el.className =
      'toast card px-4 py-2.5 text-sm shadow-lg ' + (kind === 'error' ? 'text-red-300 border-red-900' : 'text-emerald-300');
    el.textContent = message;
    wrap.appendChild(el);
    setTimeout(() => {
      el.style.transition = 'opacity 0.25s ease';
      el.style.opacity = '0';
      setTimeout(() => el.remove(), 260);
    }, 2600);
  }

  // ------------------------------------------------------------------------ API client ----

  async function api(path, { method = 'GET', body } = {}) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;
    let res;
    try {
      res = await fetch(path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
    } catch (e) {
      throw new Error('Could not reach the server. Check your connection and try again.');
    }
    let data = null;
    try {
      data = await res.json();
    } catch (e) {
      throw new Error(`Server returned an unreadable response (HTTP ${res.status}).`);
    }
    // Only an expired/invalid *session token* should bounce back to the login screen.
    // A wrong username/password on /api/admin/login also comes back as HTTP 401, but
    // with error "invalid_credentials" — that must stay on the login form and show the
    // real reason, not be reported as "your session expired".
    if (data && data.error === 'unauthorized') {
      doLogout();
      throw new Error('Your session expired — please sign in again.');
    }
    if (!data || data.ok !== true) {
      throw new Error(friendlyError(data && data.error, res.status));
    }
    return data;
  }

  function friendlyError(code, status) {
    const map = {
      invalid_credentials: 'Incorrect username or password.',
      unauthorized: 'You need to sign in again.',
      not_found: 'That no longer exists — try refreshing.',
      invalid_config: 'Pick a valid config first.',
      invalid_duration: 'Enter a valid duration.',
      invalid_device_limit: 'Enter a valid device limit.',
      name_required: 'Give this config a name.',
      config_required: 'Paste a config before saving.',
      config_in_use: 'This config is still assigned to one or more keys.',
      new_password_too_short: 'New password must be at least 6 characters.',
      rate_limited: 'Too many attempts — wait a moment and try again.',
      key_generation_failed: 'Could not generate a unique key — try again.',
    };
    return (code && map[code]) || `Something went wrong${code ? ` (${code})` : ''} (HTTP ${status}).`;
  }

  // -------------------------------------------------------------------------- auth/login ----

  function doLogout() {
    token = null;
    localStorage.removeItem(TOKEN_KEY);
    hide($('appShell'));
    show($('loginScreen'));
  }

  $('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    hide($('loginError'));
    const username = $('loginUsername').value.trim();
    const password = $('loginPassword').value;
    $('loginSubmit').disabled = true;
    try {
      const data = await api('/api/admin/login', { method: 'POST', body: { username, password } });
      token = data.token;
      localStorage.setItem(TOKEN_KEY, token);
      $('loginPassword').value = '';
      await enterApp();
    } catch (err) {
      $('loginError').textContent = err.message;
      show($('loginError'));
    } finally {
      $('loginSubmit').disabled = false;
    }
  });

  $('logoutBtn').addEventListener('click', doLogout);

  async function enterApp() {
    hide($('loginScreen'));
    show($('appShell'));
    await refreshAll();
  }

  // ------------------------------------------------------------------------- tab routing ----

  function setTab(tab) {
    document.querySelectorAll('.tab-panel').forEach((p) => hide(p));
    show($(`tab-${tab}`));
    document.querySelectorAll('.nav-btn').forEach((b) => {
      b.classList.toggle('bg-white/5', b.dataset.tab === tab);
      b.classList.toggle('text-white', b.dataset.tab === tab);
    });
    $('mobileNav').value = tab;
    if (tab === 'dashboard') loadStats();
    if (tab === 'keys') loadKeys();
    if (tab === 'configs') loadConfigs();
  }

  document.querySelectorAll('.nav-btn').forEach((b) => b.addEventListener('click', () => setTab(b.dataset.tab)));
  $('mobileNav').addEventListener('change', (e) => setTab(e.target.value));

  async function refreshAll() {
    setTab('dashboard');
    await loadConfigs(); // the create-key modal needs this populated even before the Keys tab opens
  }

  // -------------------------------------------------------------------------- dashboard ----

  function statCard(label, value, accent) {
    return `<div class="card p-5">
      <div class="text-xs text-slate-400 uppercase tracking-wide">${esc(label)}</div>
      <div class="text-3xl font-extrabold mt-1 ${accent || ''}">${esc(value)}</div>
    </div>`;
  }

  async function loadStats() {
    try {
      const d = await api('/api/admin/stats');
      $('statCards').innerHTML = [
        statCard('Active keys', d.activeKeys, 'text-emerald-400'),
        statCard('Unused keys', d.unusedKeys),
        statCard('Blocked keys', d.blockedKeys, 'text-red-400'),
        statCard('Expired keys', d.expiredKeys, 'text-amber-400'),
        statCard('Devices online', d.onlineDevices, 'brand-gradient'),
        statCard('Devices total', d.totalDevices),
        statCard('Total keys', d.totalKeys),
        statCard('Configs', d.totalConfigs),
      ].join('');
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  // ---------------------------------------------------------------------------- configs ----

  async function loadConfigs() {
    try {
      const d = await api('/api/admin/configs');
      configsCache = d.configs;
      const sel = $('createKeyConfig');
      const prev = sel.value;
      sel.innerHTML = configsCache.length
        ? configsCache.map((c) => `<option value="${c.id}">${esc(c.name)}</option>`).join('')
        : '<option value="">No configs yet — add one first</option>';
      if (prev && configsCache.some((c) => String(c.id) === prev)) sel.value = prev;

      const body = $('configsBody');
      if (!configsCache.length) {
        body.innerHTML = '';
        show($('configsEmpty'));
      } else {
        hide($('configsEmpty'));
        body.innerHTML = configsCache
          .map(
            (c) => `<tr>
              <td>${esc(c.name)}</td>
              <td class="text-slate-400">${(c.size / 1024).toFixed(1)} KB</td>
              <td>${c.keyCount}</td>
              <td class="text-slate-400">${new Date(c.created_at).toLocaleDateString()}</td>
              <td class="text-right">
                <button class="btn-ghost px-3 py-1 text-xs delete-config" data-id="${c.id}" data-name="${esc(c.name)}">Delete</button>
              </td>
            </tr>`,
          )
          .join('');
      }
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  $('addConfigBtn').addEventListener('click', async () => {
    const name = $('newConfigName').value.trim();
    const configText = $('newConfigText').value.trim();
    if (!name || !configText) {
      toast('Enter a name and paste a config first.', 'error');
      return;
    }
    $('addConfigBtn').disabled = true;
    try {
      await api('/api/admin/configs', { method: 'POST', body: { name, configText } });
      $('newConfigName').value = '';
      $('newConfigText').value = '';
      toast('Config added.');
      await loadConfigs();
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      $('addConfigBtn').disabled = false;
    }
  });

  $('configsBody').addEventListener('click', async (e) => {
    const btn = e.target.closest('.delete-config');
    if (!btn) return;
    if (!confirm(`Delete config "${btn.dataset.name}"? This can't be undone.`)) return;
    try {
      await api(`/api/admin/configs/${btn.dataset.id}`, { method: 'DELETE' });
      toast('Config deleted.');
      await loadConfigs();
    } catch (err) {
      toast(err.message, 'error');
    }
  });

  // ------------------------------------------------------------------------------- keys ----

  const STATUS_BADGE = {
    active: 'badge-active',
    unused: 'badge-unused',
    blocked: 'badge-blocked',
    expired: 'badge-expired',
  };

  function fmtRemaining(expiresAt) {
    if (expiresAt == null) return 'Not started';
    const ms = expiresAt - Date.now();
    if (ms <= 0) return 'Expired';
    const mins = Math.floor(ms / 60000);
    const d = Math.floor(mins / 1440);
    const h = Math.floor((mins % 1440) / 60);
    const m = mins % 60;
    if (d > 0) return `${d}d ${h}h left`;
    if (h > 0) return `${h}h ${m}m left`;
    return `${m}m left`;
  }

  async function loadKeys() {
    try {
      const params = new URLSearchParams();
      const search = $('keySearch').value.trim();
      const status = $('keyStatusFilter').value;
      if (search) params.set('search', search);
      if (status) params.set('status', status);
      const d = await api(`/api/admin/keys?${params.toString()}`);
      const body = $('keysBody');
      if (!d.keys.length) {
        body.innerHTML = '';
        show($('keysEmpty'));
        return;
      }
      hide($('keysEmpty'));
      body.innerHTML = d.keys
        .map((k) => {
          const badge = STATUS_BADGE[k.status] || 'badge-unused';
          const blockBtn =
            k.rawStatus === 'blocked'
              ? `<button class="btn-ghost px-3 py-1 text-xs unblock-key" data-id="${k.id}">Unblock</button>`
              : `<button class="btn-ghost px-3 py-1 text-xs block-key" data-id="${k.id}">Block</button>`;
          return `<tr>
            <td class="mono">${esc(k.keyCode)}</td>
            <td class="text-slate-400">${esc(k.configName)}</td>
            <td><span class="badge ${badge}">${esc(k.status)}</span></td>
            <td>
              <button class="underline decoration-dotted view-devices" data-id="${k.id}" data-code="${esc(k.keyCode)}">
                ${k.deviceCount}/${k.deviceLimit}
              </button>
            </td>
            <td class="text-slate-400 whitespace-nowrap">${fmtRemaining(k.expiresAt)}</td>
            <td class="text-slate-400">${esc(k.note || '—')}</td>
            <td class="text-right whitespace-nowrap space-x-1">
              ${blockBtn}
              <button class="btn-ghost px-3 py-1 text-xs reset-key" data-id="${k.id}" title="Clear devices and restart the expiry timer">Reset</button>
              <button class="btn-ghost px-3 py-1 text-xs delete-key" data-id="${k.id}" data-code="${esc(k.keyCode)}">Delete</button>
            </td>
          </tr>`;
        })
        .join('');
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  let keySearchDebounce;
  $('keySearch').addEventListener('input', () => {
    clearTimeout(keySearchDebounce);
    keySearchDebounce = setTimeout(loadKeys, 250);
  });
  $('keyStatusFilter').addEventListener('change', loadKeys);

  $('keysBody').addEventListener('click', async (e) => {
    const id = e.target.dataset.id;
    if (!id) return;
    try {
      if (e.target.classList.contains('block-key')) {
        await api(`/api/admin/keys/${id}/block`, { method: 'POST' });
        toast('Key blocked.');
        await loadKeys();
      } else if (e.target.classList.contains('unblock-key')) {
        await api(`/api/admin/keys/${id}/unblock`, { method: 'POST' });
        toast('Key unblocked.');
        await loadKeys();
      } else if (e.target.classList.contains('reset-key')) {
        if (!confirm('Reset this key? This clears its devices and restarts the expiry timer on next use.')) return;
        await api(`/api/admin/keys/${id}/reset`, { method: 'POST' });
        toast('Key reset.');
        await loadKeys();
      } else if (e.target.classList.contains('delete-key')) {
        if (!confirm(`Delete key "${e.target.dataset.code}"? This can't be undone.`)) return;
        await api(`/api/admin/keys/${id}`, { method: 'DELETE' });
        toast('Key deleted.');
        await loadKeys();
        loadStats();
      } else if (e.target.classList.contains('view-devices')) {
        openDevicesModal(id, e.target.dataset.code);
      }
    } catch (err) {
      toast(err.message, 'error');
    }
  });

  // --------------------------------------------------------------------- create-key modal ----

  let selectedDurationSecs = null;

  function openModal(id) { show($(id)); }
  function closeModal(id) { hide($(id)); }

  document.querySelectorAll('.modal-close').forEach((btn) =>
    btn.addEventListener('click', () => closeModal(btn.dataset.modal)),
  );
  document.querySelectorAll('[id$="Modal"]').forEach((modal) => {
    modal.addEventListener('click', (e) => {
      if (e.target === modal) closeModal(modal.id);
    });
  });

  $('openCreateKey').addEventListener('click', async () => {
    await loadConfigs();
    if (!configsCache.length) {
      toast('Add a config first — Keys need one to connect through.', 'error');
      setTab('configs');
      return;
    }
    $('createKeyDeviceLimit').value = 1;
    $('createKeyQuantity').value = 1;
    $('createKeyNote').value = '';
    $('customDurationSecs').value = '';
    selectedDurationSecs = null;
    document.querySelectorAll('.duration-btn').forEach((b) => b.classList.remove('ring-2', 'ring-cyan-400'));
    hide($('createKeyError'));
    hide($('createKeyResult'));
    $('createKeyResultList').innerHTML = '';
    openModal('createKeyModal');
  });

  document.querySelectorAll('.duration-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      selectedDurationSecs = Number(btn.dataset.secs);
      $('customDurationSecs').value = '';
      document.querySelectorAll('.duration-btn').forEach((b) => b.classList.remove('ring-2', 'ring-cyan-400'));
      btn.classList.add('ring-2', 'ring-cyan-400');
    });
  });
  $('customDurationSecs').addEventListener('input', () => {
    document.querySelectorAll('.duration-btn').forEach((b) => b.classList.remove('ring-2', 'ring-cyan-400'));
    selectedDurationSecs = null;
  });

  $('submitCreateKey').addEventListener('click', async () => {
    hide($('createKeyError'));
    const configId = Number($('createKeyConfig').value);
    const customSecs = Number($('customDurationSecs').value);
    const durationSeconds = customSecs > 0 ? customSecs : selectedDurationSecs;
    const deviceLimit = Number($('createKeyDeviceLimit').value);
    const quantity = Number($('createKeyQuantity').value);
    const note = $('createKeyNote').value.trim();

    if (!configId) { showCreateKeyError('Pick a config.'); return; }
    if (!durationSeconds || durationSeconds <= 0) { showCreateKeyError('Pick or enter a duration.'); return; }
    if (!deviceLimit || deviceLimit <= 0) { showCreateKeyError('Enter a device limit of at least 1.'); return; }

    $('submitCreateKey').disabled = true;
    try {
      const d = await api('/api/admin/keys', {
        method: 'POST',
        body: { configId, durationSeconds, deviceLimit, quantity, note: note || undefined },
      });
      $('createKeyResultList').innerHTML = d.created
        .map((k) => `<div class="mono text-sm bg-black/30 rounded px-2 py-1">${esc(k.keyCode)}</div>`)
        .join('');
      show($('createKeyResult'));
      toast(`Generated ${d.created.length} key(s).`);
      await loadKeys();
      loadStats();
    } catch (err) {
      showCreateKeyError(err.message);
    } finally {
      $('submitCreateKey').disabled = false;
    }
  });

  function showCreateKeyError(msg) {
    $('createKeyError').textContent = msg;
    show($('createKeyError'));
  }

  $('copyAllKeys').addEventListener('click', async () => {
    const codes = Array.from($('createKeyResultList').children).map((el) => el.textContent);
    try {
      await navigator.clipboard.writeText(codes.join('\n'));
      toast('Copied to clipboard.');
    } catch (e) {
      toast('Could not copy — select and copy manually.', 'error');
    }
  });

  // ------------------------------------------------------------------------ devices modal ----

  let devicesModalKeyId = null;

  async function openDevicesModal(keyId, keyCode) {
    devicesModalKeyId = keyId;
    $('devicesModalKeyCode').textContent = keyCode;
    $('devicesList').innerHTML = '<div class="text-sm text-slate-500">Loading…</div>';
    openModal('devicesModal');
    await refreshDevicesModal();
  }

  async function refreshDevicesModal() {
    if (!devicesModalKeyId) return;
    try {
      const d = await api(`/api/admin/keys/${devicesModalKeyId}`);
      if (!d.devices.length) {
        $('devicesList').innerHTML = '<div class="text-sm text-slate-500 py-6 text-center">No devices yet.</div>';
        return;
      }
      $('devicesList').innerHTML = d.devices
        .map((dev) => {
          const dot = dev.online ? 'dot-online' : 'dot-offline';
          const kicked = dev.status === 'kicked';
          return `<div class="card p-3 flex items-center justify-between gap-3">
            <div class="min-w-0">
              <div class="flex items-center gap-2">
                <span class="dot ${dot}"></span>
                <span class="font-medium truncate">${esc(dev.deviceName || 'Unknown device')}</span>
                ${kicked ? '<span class="badge badge-blocked">kicked</span>' : ''}
              </div>
              <div class="text-xs text-slate-500 mono mt-1 truncate">${esc(dev.deviceId)}</div>
              <div class="text-xs text-slate-500 mt-0.5">
                Last seen ${timeAgo(dev.lastSeenAt)} · Ping ${dev.lastPingMs != null ? dev.lastPingMs + ' ms' : '—'}
              </div>
            </div>
            ${
              kicked
                ? ''
                : `<button class="btn-ghost px-3 py-1 text-xs shrink-0 kick-device" data-id="${dev.id}">Kick</button>`
            }
          </div>`;
        })
        .join('');
    } catch (err) {
      $('devicesList').innerHTML = `<div class="text-sm text-red-400">${esc(err.message)}</div>`;
    }
  }

  $('devicesList').addEventListener('click', async (e) => {
    const btn = e.target.closest('.kick-device');
    if (!btn) return;
    try {
      await api(`/api/admin/devices/${btn.dataset.id}/kick`, { method: 'POST' });
      toast('Device kicked — it will be disconnected on its next check-in.');
      await refreshDevicesModal();
      await loadKeys();
    } catch (err) {
      toast(err.message, 'error');
    }
  });

  function timeAgo(ts) {
    const sec = Math.max(0, Math.floor((Date.now() - ts) / 1000));
    if (sec < 60) return 'just now';
    const min = Math.floor(sec / 60);
    if (min < 60) return `${min}m ago`;
    const hr = Math.floor(min / 60);
    if (hr < 24) return `${hr}h ago`;
    return `${Math.floor(hr / 24)}d ago`;
  }

  // ------------------------------------------------------------------------------- boot ----

  if (token) {
    enterApp().catch(() => doLogout());
  } else {
    show($('loginScreen'));
  }

  // Keep "time left" / "last seen" copy fresh without refetching.
  setInterval(() => {
    if (!$('tab-keys').classList.contains('hidden')) loadKeys();
  }, 30_000);
})();
