// Fallback page of Core's settings module for web UIs without /app/settings (vystorm-web-ui < 0.7.0).
// Same API as the web UI page: GET /api/settings/sections, POST /api/settings/save, live event settings/changed.
(function () {
  'use strict';
  var W = window.VystormWeb;
  var el = W.el;

  function status(text, error) {
    var box = document.getElementById('status');
    box.className = error ? 'vw-notice vw-error' : 'vw-muted';
    box.textContent = text || '';
  }

  function problemText(p) {
    if (p.code === 'rejected') return W.t('settings.rejected', { label: p.label, message: p.message || '' });
    if (p.code === 'failed') return W.t('settings.failed', { label: p.label });
    return W.t('settings.invalid', { label: p.label });
  }

  async function save(section, id, value) {
    status(W.t('settings.saving'));
    try {
      var values = {};
      values[id] = value;
      var r = await W.post('/api/settings/save', { section: section, values: values });
      var problems = (r.problems || []).map(problemText);
      if (r.applyFailed) problems.push(W.t('settings.apply-failed'));
      status(problems.length ? problems.join(' · ') : W.t('settings.saved'), problems.length > 0);
      if (problems.length) load();
    } catch (e) {
      status(e.message, true);
      load();
    }
  }

  function control(section, s) {
    if (!s.readable) return el('span', { class: 'vw-muted' }, W.t('settings.unreadable'));
    var label = s.label;
    if (s.kind === 'toggle') {
      return el('input', { type: 'checkbox', 'aria-label': label, checked: s.value === true,
        onchange: function (e) { save(section, s.id, e.target.checked); } });
    }
    if (s.kind === 'number') {
      return el('span', null,
        el('input', { class: 'vw-input', type: 'number', min: s.min, max: s.max, step: s.step, value: s.value, 'aria-label': label,
          onchange: function (e) { var v = Number(e.target.value); if (isFinite(v)) save(section, s.id, v); } }),
        ' ', el('span', { class: 'vw-muted' }, W.t('settings.range', { min: s.min, max: s.max })));
    }
    if (s.kind === 'choice') {
      return el('select', { class: 'vw-input', 'aria-label': label, onchange: function (e) { save(section, s.id, e.target.value); } },
        (s.options || []).map(function (o) { return el('option', { value: o.id, selected: o.id === s.value }, o.label); }));
    }
    return el('input', { class: 'vw-input', type: 'text', maxlength: s.maxLength, value: s.value || '', 'aria-label': label,
      onchange: function (e) { save(section, s.id, e.target.value); } });
  }

  function render(data) {
    var host = document.getElementById('sections');
    host.textContent = '';
    if (!data.supported) { status(W.t('settings.unsupported', { command: '/vsettings' })); return; }
    if (!data.online) { status(W.t('settings.offline')); return; }
    if (!data.sections.length) { status(W.t('settings.empty')); return; }
    status('');
    ['player', 'server'].forEach(function (scope) {
      var list = data.sections.filter(function (x) { return x.scope === scope; });
      if (!list.length) return;
      host.appendChild(el('h2', null, W.t(scope === 'server' ? 'settings.server' : 'settings.personal')));
      host.appendChild(el('p', { class: 'vw-muted' }, W.t(scope === 'server' ? 'settings.server-hint' : 'settings.personal-hint')));
      list.forEach(function (section) {
        var rows = section.settings.map(function (s) {
          return el('tr', null,
            el('th', { scope: 'row' }, s.label, s.description ? el('div', { class: 'vw-muted' }, s.description) : null),
            el('td', null, control(section.key, s)));
        });
        host.appendChild(el('div', { class: 'vw-card' },
          el('h3', null, section.title),
          section.description ? el('p', { class: 'vw-muted' }, section.description) : null,
          el('table', { class: 'vw-table', 'aria-label': section.title }, el('tbody', null, rows))));
      });
    });
  }

  async function load() {
    try { render(await W.get('/api/settings/sections')); }
    catch (e) { if (e.status !== 401) status(e.message, true); }
  }

  document.addEventListener('DOMContentLoaded', function () {
    load();
    W.live(['settings', 'core'], function (message) {
      if (message.kind !== 'event' || message.snapshot) return;
      if (message.module === 'settings' && message.type === 'changed') load();
      if (message.module === 'core' && message.type === 'status') load();
    });
  });
})();
