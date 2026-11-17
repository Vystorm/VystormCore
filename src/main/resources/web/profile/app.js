(function () {
  'use strict';
  var W = window.VystormWeb;

  function row(label, value) {
    return W.el('tr', null, W.el('th', { scope: 'row' }, label), W.el('td', null, value));
  }

  async function load() {
    var body = document.getElementById('profile');
    try {
      var p = await W.get('/api/core/profile');
      body.textContent = '';
      [
        row(W.t('profile.name'), p.name),
        row('UUID', W.el('span', { class: 'vw-mono' }, p.uuid)),
        row(W.t('profile.status'), p.online ? W.t('profile.online') : W.t('profile.offline')),
        p.world ? row(W.t('profile.world'), p.world) : null,
        p.level !== undefined ? row(W.t('profile.level'), String(p.level)) : null,
        p.health !== undefined ? row(W.t('profile.health'), String(p.health)) : null,
        row('Vystorm Core', p.core)
      ].filter(Boolean).forEach(function (r) { body.appendChild(r); });
    } catch (e) {
      if (e.status !== 401) W.showError(e);
    }
  }

  function showStatus(status) {
    var box = document.getElementById('live');
    if (!status.online) { box.textContent = W.t('profile.offline-in-game'); return; }
    box.textContent = W.t('profile.online-in', { world: status.world, level: status.level });
  }

  document.addEventListener('DOMContentLoaded', function () {
    load();
    W.live(['core'], function (message) {
      if (message.kind === 'event' && message.module === 'core' && message.type === 'status') showStatus(message.data);
    });
    document.getElementById('echo').addEventListener('submit', async function (event) {
      event.preventDefault();
      var out = document.getElementById('echo-result');
      var message = document.getElementById('message').value || W.t('profile.echo-placeholder');
      try {
        var r = await W.post('/api/core/echo', { message: message });
        out.textContent = W.t('profile.answer', { echo: r.echo, at: r.at });
      } catch (e) {
        out.textContent = W.t('profile.error', { message: e.message });
      }
    });
  });
})();
