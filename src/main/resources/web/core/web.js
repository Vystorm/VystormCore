/*
 * Vystorm Web – gemeinsamer Helfer für alle Modulseiten. Einbinden mit
 *   <script src="/core/web.js"></script>
 * vor dem eigenen Skript. Stellt window.VystormWeb bereit:
 *   await VystormWeb.session()            -> { player, uuid, online, modules, ... } oder null (nicht angemeldet)
 *   await VystormWeb.get('/api/<modul>/x') -> JSON
 *   await VystormWeb.post('/api/<modul>/y', { ... })  (CSRF-Header wird automatisch gesetzt)
 *   VystormWeb.el('div', { class: 'vw-card' }, 'Text', kind)  -> sicheres DOM ohne innerHTML
 *   VystormWeb.live(['skills'], function (msg) { ... })  -> Live-Kanal (Server-Sent Events), liefert
 *       { kind: 'event', module, type, data, snapshot } / { kind: 'ready' } / { kind: 'data', ... }; gibt close() zurück
 * Fehler werfen VystormWeb.WebError { status, code, message }. Bei 401 erscheint ein Hinweis zum Neu-Anmelden.
 * Ein Element mit data-vw-header wird zur gemeinsamen Kopfzeile (Name, Übersicht, Theme, Sprache, Abmelden).
 *   VystormWeb.t('index.choose', { player: 'Alex' })  -> Text in der Seitensprache (Core füllt data-vw-texts im <html>-Element
 *       aus lang/<code>.yml, Schlüssel unter web.ui.*; Plugins: {{plugin:web.ui.x}} im HTML oder GET /core/i18n?ns=<plugin>)
 *   VystormWeb.setLanguage('en')                      -> manuelle Sprachwahl (Cookie vystorm_lang), lädt die Seite neu
 */
(function () {
  'use strict';

  var csrf = null;
  var sessionPromise = null;

  function attrJson(name, fallback) {
    try { return JSON.parse(document.documentElement.getAttribute(name) || '') || fallback; } catch (e) { return fallback; }
  }
  var texts = attrJson('data-vw-texts', {});
  var languages = attrJson('data-vw-languages', []);

  function t(key, vars) {
    var text = typeof texts[key] === 'string' ? texts[key] : key;
    return text.replace(/\{([A-Za-z0-9_.-]+)\}/g, function (all, name) { return vars && vars[name] !== undefined ? String(vars[name]) : all; });
  }

  function setLanguage(code) {
    if (!/^[a-z]{1,8}(_[a-z0-9]{1,8})*$/.test(code)) return;
    document.cookie = 'vystorm_lang=' + code + '; path=/; max-age=31536000; SameSite=Lax' + (location.protocol === 'https:' ? '; Secure' : '');
    location.reload();
  }

  function WebError(status, code, message) {
    this.name = 'WebError';
    this.status = status;
    this.code = code;
    this.message = message || ('HTTP ' + status);
  }
  WebError.prototype = Object.create(Error.prototype);

  function el(tag, attrs) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        var value = attrs[key];
        if (value === null || value === undefined || value === false) return;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = value;
        else if (key.slice(0, 2) === 'on' && typeof value === 'function') node.addEventListener(key.slice(2), value);
        else if (key === 'href' && !/^(\/|https?:)/.test(String(value))) return;
        else node.setAttribute(key, value === true ? '' : String(value));
      });
    }
    for (var i = 2; i < arguments.length; i++) append(node, arguments[i]);
    return node;
  }

  function append(node, child) {
    if (child === null || child === undefined || child === false) return;
    if (Array.isArray(child)) { child.forEach(function (c) { append(node, c); }); return; }
    node.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
  }

  async function readJson(response) {
    var text = await response.text();
    if (!text) return null;
    try { return JSON.parse(text); } catch (e) { return null; }
  }

  async function session(force) {
    if (!sessionPromise || force) {
      sessionPromise = (async function () {
        var response = await fetch('/core/session', { credentials: 'same-origin', headers: { Accept: 'application/json' }, cache: 'no-store' });
        if (response.status === 401) { csrf = null; return null; }
        var data = await readJson(response);
        if (!response.ok) throw new WebError(response.status, data && data.error, data && data.message);
        csrf = data.csrf;
        return data;
      })();
      sessionPromise.catch(function () { sessionPromise = null; });
    }
    return sessionPromise;
  }

  async function request(path, options) {
    options = options || {};
    var method = (options.method || 'GET').toUpperCase();
    var headers = { Accept: 'application/json' };
    if (method !== 'GET') {
      if (!csrf) await session(true);
      if (csrf) headers['X-Vystorm-CSRF'] = csrf;
    }
    var body;
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(options.body);
    }
    var response = await fetch(path, { method: method, headers: headers, body: body, credentials: 'same-origin', cache: 'no-store' });
    var data = await readJson(response);
    if (response.status === 401) { csrf = null; sessionPromise = null; showLoginHint(); }
    if (!response.ok) throw new WebError(response.status, data && data.error, data && data.message);
    return data;
  }

  function showLoginHint() {
    if (document.getElementById('vw-login-hint')) return;
    var parts = t('login-hint').split('{command}');
    var hint = el('div', { id: 'vw-login-hint', class: 'vw-notice vw-error', role: 'alert' },
      parts[0], el('span', { class: 'vw-mono' }, '/web'), parts.slice(1).join('/web'));
    var main = document.querySelector('main') || document.body;
    main.insertBefore(hint, main.firstChild);
  }

  function showError(error, target) {
    var message = error && error.message ? error.message : String(error);
    var box = el('div', { class: 'vw-notice vw-error', role: 'alert' }, message);
    var main = target || document.querySelector('main') || document.body;
    main.insertBefore(box, main.firstChild);
    return box;
  }

  async function logout() {
    try { await request('/core/logout', { method: 'POST' }); } catch (e) { /* bereits abgemeldet */ }
    csrf = null;
    sessionPromise = null;
    location.href = '/';
  }

  function live(modules, onMessage) {
    var url = '/core/live' + (modules && modules.length ? '?modules=' + encodeURIComponent(modules.join(',')) : '');
    var source = new EventSource(url);
    source.onmessage = function (event) {
      var message;
      try { message = JSON.parse(event.data); } catch (e) { return; }
      if (message.kind === 'bye' && message.reason !== 'resync') source.close();
      if (message.kind === 'bye' && (message.reason === 'logout' || message.reason === 'session')) showLoginHint();
      onMessage(message);
    };
    return { close: function () { source.close(); } };
  }

  function applyTheme(theme) {
    if (theme === 'light' || theme === 'dark') document.documentElement.setAttribute('data-theme', theme);
    else document.documentElement.removeAttribute('data-theme');
  }

  function toggleTheme() {
    var current = document.documentElement.getAttribute('data-theme');
    var dark = current ? current === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
    var next = dark ? 'light' : 'dark';
    applyTheme(next);
    try { localStorage.setItem('vw-theme', next); } catch (e) { /* ohne Speicher */ }
  }

  try { applyTheme(localStorage.getItem('vw-theme')); } catch (e) { /* ohne Speicher */ }

  async function renderHeader(host) {
    host.classList.add('vw-bar');
    host.textContent = '';
    var user = el('span', { class: 'vw-user' });
    var logoutButton = el('button', { class: 'vw-button vw-hidden', type: 'button', onclick: logout }, t('header.logout'));
    var current = (document.documentElement.getAttribute('lang') || '').toLowerCase().replace('-', '_');
    var picker = languages.length > 1 ? el('select', { class: 'vw-input vw-lang', title: t('header.language'), 'aria-label': t('header.language'),
      onchange: function (event) { setLanguage(event.target.value); } },
      languages.map(function (l) { return el('option', { value: l.code, selected: l.code === current }, l.name); })) : null;
    host.append(
      el('a', { class: 'vw-brand', href: '/' }, 'Vystorm'),
      user,
      picker,
      el('button', { class: 'vw-button', type: 'button', title: t('header.theme'), 'aria-label': t('header.theme-toggle'), onclick: toggleTheme }, '◐'),
      logoutButton);
    try {
      var s = await session();
      if (s) {
        user.textContent = s.player + ' • ' + (s.online ? t('header.online') : t('header.offline'));
        logoutButton.classList.remove('vw-hidden');
      }
    } catch (e) { /* Kopfzeile bleibt schlicht */ }
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-vw-header]').forEach(function (host) { renderHeader(host); });
  });

  window.VystormWeb = Object.freeze({
    WebError: WebError,
    session: session,
    request: request,
    get: function (path) { return request(path); },
    post: function (path, body) { return request(path, { method: 'POST', body: body }); },
    put: function (path, body) { return request(path, { method: 'PUT', body: body }); },
    patch: function (path, body) { return request(path, { method: 'PATCH', body: body }); },
    del: function (path) { return request(path, { method: 'DELETE' }); },
    logout: logout,
    live: live,
    el: el,
    showError: showError,
    showLoginHint: showLoginHint,
    toggleTheme: toggleTheme,
    t: t,
    setLanguage: setLanguage,
    languages: languages
  });
})();
