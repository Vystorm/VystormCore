(function () {
  'use strict';
  var W = window.VystormWeb;
  document.addEventListener('DOMContentLoaded', async function () {
    var status = document.getElementById('status');
    var list = document.getElementById('modules');
    try {
      var s = await W.session();
      if (!s) {
        status.textContent = W.t('index.not-logged-in');
        return;
      }
      status.textContent = W.t('index.choose', { player: s.player });
      if (!s.modules.length) status.textContent = W.t('index.none', { player: s.player });
      s.modules.forEach(function (m) {
        list.appendChild(W.el('a', { class: 'vw-card', href: m.url }, W.el('h3', null, m.title), W.el('p', null, m.description || '')));
      });
    } catch (e) {
      status.textContent = '';
      W.showError(e);
    }
  });
})();
