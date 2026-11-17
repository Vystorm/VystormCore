(function () {
  'use strict';
  // Das Token steht im Fragment (#…): es geht nie an Server-, Proxy-Logs oder Referer. Sofort aus der Adresse entfernen.
  var token = location.hash.length > 1 ? location.hash.slice(1) : '';
  history.replaceState(null, '', '/login');

  // Texts of the page language (Core fills data-vw-texts on the <html> element).
  var texts = {};
  try { texts = JSON.parse(document.documentElement.getAttribute('data-vw-texts') || '{}'); } catch (e) { texts = {}; }
  function t(key, vars) {
    var text = typeof texts[key] === 'string' ? texts[key] : key;
    return text.replace(/\{([A-Za-z0-9_.-]+)\}/g, function (all, name) { return vars && vars[name] !== undefined ? String(vars[name]) : all; });
  }

  function show(text, ok) {
    var status = document.getElementById('status');
    if (!status) return;
    status.textContent = text;
    status.className = 'vw-notice ' + (ok ? 'vw-ok' : 'vw-error');
  }

  async function login() {
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) {
      show(t('login.bad-link'), false);
      return;
    }
    try {
      var response = await fetch('/core/login', {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ token: token })
      });
      token = '';
      var data = null;
      try { data = await response.json(); } catch (e) { data = null; }
      if (!response.ok) {
        show((data && data.message) || t('login.failed', { status: response.status }), false);
        return;
      }
      show(t('login.success', { player: data.player }), true);
      var target = typeof data.redirect === 'string' && /^\/(m\/[a-z0-9-]{1,32}\/)?$/.test(data.redirect) ? data.redirect : '/';
      location.replace(target);
    } catch (e) {
      show(t('login.unreachable'), false);
    }
  }

  document.addEventListener('DOMContentLoaded', login);
})();
