// Изи Защитник для Windows: экран. Методы программы — window.go.main.App, события — window.runtime.
(function () {
  'use strict';

  var api = function () { return window.go.main.App; };
  var $ = function (id) { return document.getElementById(id); };

  var state = null;
  var shownErrorSeq = null;
  var adding = false;
  var refreshingId = null;
  var openSheet = null;

  // ---------- оформление ----------

  function pad(n) { return n < 10 ? '0' + n : String(n); }

  function uptime(startedAt) {
    var total = Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
    var h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60;
    return pad(h) + ':' + pad(m) + ':' + pad(s);
  }

  var months = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  function updatedText(ms) {
    if (!ms || ms <= 0) { return ''; }
    var d = new Date(ms);
    return 'Обновлено ' + d.getDate() + ' ' + months[d.getMonth()] + ', ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  // Служебное «auto» показываем как «Авто».
  function serverTitle(tag, autoServer) {
    if (!tag) { return ''; }
    if (tag.toLowerCase() !== 'auto') { return tag; }
    return autoServer ? 'Авто · ' + autoServer : 'Авто — самый быстрый';
  }

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) { node.className = cls; }
    if (text !== undefined) { node.textContent = text; }
    return node;
  }

  var dotsIcon = '<svg aria-hidden="true" viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><circle cx="12" cy="5" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="12" cy="19" r="1.8"/></svg>';

  // ---------- отрисовка ----------

  function render() {
    if (!state) { return; }
    var status = state.status;
    var hasProfiles = state.profiles.length > 0;
    var on = status === 'started';
    var busy = status === 'starting' || status === 'stopping';

    var power = $('power');
    power.classList.toggle('on', on);
    power.classList.toggle('disabled', !hasProfiles);
    power.disabled = !hasProfiles || busy;
    $('glow').classList.toggle('on', on);
    $('power-icon').classList.toggle('hidden', busy);
    $('power-spinner').classList.toggle('hidden', !busy);
    $('power-label').textContent = busy ? 'Подождите' : (on ? 'Выключить' : 'Включить');

    var title = $('status-title'), sub = $('status-sub');
    title.classList.toggle('on', on);
    if (!hasProfiles && status === 'stopped') {
      title.textContent = 'Сначала добавьте подключение';
      sub.textContent = '';
    } else if (status === 'stopped') {
      title.textContent = 'Защита выключена';
      sub.textContent = 'Нажмите, чтобы включить';
    } else if (status === 'starting') {
      title.textContent = 'Включаем…';
      sub.textContent = '';
    } else if (status === 'stopping') {
      title.textContent = 'Выключаем…';
      sub.textContent = '';
    } else {
      title.textContent = 'Защита включена';
      sub.textContent = 'Работает ' + uptime(state.startedAt);
    }

    // Сервер
    var showServers = hasProfiles && state.servers.length > 1;
    $('server-row').classList.toggle('hidden', !showServers);
    $('server-title').textContent = serverTitle(state.server, state.autoServer);
    if (openSheet === 'servers') { renderServers(); }

    // Подключения
    $('profiles-block').classList.toggle('hidden', !hasProfiles);
    $('empty').classList.toggle('hidden', hasProfiles);
    var list = $('profiles');
    list.textContent = '';
    state.profiles.forEach(function (p) {
      var selected = p.id === state.selected;
      var row = el('div', 'profile' + (selected ? ' selected' : ''));
      row.setAttribute('role', 'button');
      row.tabIndex = 0;
      row.appendChild(el('span', 'radio' + (selected ? ' on' : '')));
      var text = el('div', 'profile-text');
      text.appendChild(el('div', 'profile-name', p.name));
      var sub = updatedText(p.updated);
      if (sub) { text.appendChild(el('div', 'profile-sub', sub)); }
      row.appendChild(text);
      var action;
      if (refreshingId === p.id) {
        action = el('span', 'icon-btn');
        action.appendChild(el('span', 'spinner'));
      } else {
        action = el('button', 'icon-btn');
        action.type = 'button';
        action.setAttribute('aria-label', 'Действия');
        action.innerHTML = dotsIcon;
        action.addEventListener('click', function (event) {
          event.stopPropagation();
          showProfileMenu(p, action);
        });
      }
      row.appendChild(action);
      var pick = function () { if (!selected) { call(api().SelectProfile(p.id)); } };
      row.addEventListener('click', pick);
      row.addEventListener('keydown', function (event) { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); pick(); } });
      list.appendChild(row);
    });

    // Добавить
    var add = $('add');
    add.classList.toggle('primary', !hasProfiles);
    add.disabled = adding;
    $('add-icon').classList.toggle('hidden', adding);
    $('add-spinner').classList.toggle('hidden', !adding);
    $('add-label').textContent = adding ? 'Добавляем…' : 'Добавить';

    // Ошибка включения
    if (shownErrorSeq === null) {
      shownErrorSeq = state.errorSeq;
    } else if (state.errorSeq !== shownErrorSeq) {
      shownErrorSeq = state.errorSeq;
      showMessage('Не получилось', state.error);
    }
  }

  function renderServers() {
    var list = $('server-list');
    list.textContent = '';
    state.servers.forEach(function (s) {
      var selected = s.tag === state.server;
      var item = el('button', 'server-item' + (selected ? ' selected' : ''));
      item.type = 'button';
      item.appendChild(el('span', 'radio' + (selected ? ' on' : '')));
      item.appendChild(el('span', 'name', serverTitle(s.tag, selected ? state.autoServer : '')));
      if (s.delay > 0) { item.appendChild(el('span', 'delay', s.delay + ' мс')); }
      item.addEventListener('click', function () {
        closeAll();
        call(api().SelectServer(s.tag));
      });
      list.appendChild(item);
    });
  }

  // ---------- связь с программой ----------

  function refresh() {
    return api().State().then(function (next) {
      state = next;
      render();
    }).catch(function () {});
  }

  function call(promise) {
    return Promise.resolve(promise).then(refresh, function (error) {
      showMessage('Не получилось', String(error));
      return refresh();
    });
  }

  // ---------- листы и окна ----------

  function closeAll() {
    ['sheet-servers', 'sheet-master', 'dialog', 'menu', 'overlay'].forEach(function (id) { $(id).classList.add('hidden'); });
    openSheet = null;
  }

  function showSheet(name) {
    closeAll();
    openSheet = name;
    $('overlay').classList.remove('hidden');
    $('sheet-' + name).classList.remove('hidden');
    if (name === 'servers') { renderServers(); }
  }

  // dialog({title, body (узел или текст), ok, okClass, cancel, onOk}) — onOk вернёт false, чтобы окно осталось.
  function dialog(options) {
    closeAll();
    $('overlay').classList.remove('hidden');
    $('dialog').classList.remove('hidden');
    $('dialog-title').textContent = options.title || '';
    var body = $('dialog-body');
    body.textContent = '';
    if (typeof options.body === 'string') { body.textContent = options.body; } else if (options.body) { body.appendChild(options.body); }
    var ok = $('dialog-ok'), cancel = $('dialog-cancel');
    ok.textContent = options.ok || 'ОК';
    ok.className = 'text-btn ' + (options.okClass || 'accent');
    ok.disabled = false;
    cancel.classList.toggle('hidden', options.cancel === false);
    cancel.textContent = options.cancel || 'Отмена';
    ok.onclick = function () {
      if (options.onOk && options.onOk() === false) { return; }
      closeAll();
    };
    cancel.onclick = closeAll;
    return { ok: ok };
  }

  function showMessage(title, text) {
    dialog({ title: title, body: text, ok: 'Понятно', cancel: false });
  }

  var toastTimer = null;
  function toast(text) {
    var node = $('toast');
    node.textContent = text;
    node.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { node.classList.add('hidden'); }, 3200);
  }

  function showProfileMenu(profile, anchor) {
    var menu = $('menu');
    menu.textContent = '';
    var items = [
      ['Обновить', '', function () { refreshProfile(profile); }],
      ['Переименовать', '', function () { renameProfile(profile); }],
      ['Удалить', 'danger', function () { removeProfile(profile); }]
    ];
    items.forEach(function (item) {
      var button = el('button', item[1], item[0]);
      button.type = 'button';
      button.addEventListener('click', function () { menu.classList.add('hidden'); item[2](); });
      menu.appendChild(button);
    });
    menu.classList.remove('hidden');
    var rect = anchor.getBoundingClientRect();
    var width = menu.offsetWidth, height = menu.offsetHeight;
    var left = Math.min(rect.right - width, window.innerWidth - width - 8);
    var top = rect.bottom + 4;
    if (top + height > window.innerHeight - 8) { top = rect.top - height - 4; }
    menu.style.left = Math.max(8, left) + 'px';
    menu.style.top = Math.max(8, top) + 'px';
  }

  document.addEventListener('click', function (event) {
    var menu = $('menu');
    if (!menu.classList.contains('hidden') && !menu.contains(event.target)) { menu.classList.add('hidden'); }
  }, true);

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') { closeAll(); }
  });

  // ---------- действия ----------

  function addLink(link) {
    if (adding) { return; }
    adding = true;
    render();
    api().Add(link).then(function (result) {
      toast(result.refreshed ? 'Уже было добавлено, обновили: ' + result.name : 'Добавлено: ' + result.name);
    }, function (error) {
      showMessage('Не получилось', String(error));
    }).then(function () {
      adding = false;
      return refresh();
    });
  }

  function askPaste() {
    var wrap = el('div');
    wrap.appendChild(el('p', 'muted', 'В буфере обмена нет ссылки. Скопируйте ссылку, которую вам прислали, и вставьте её сюда.'));
    var label = el('label', 'field-label', 'Ссылка');
    label.setAttribute('for', 'paste-field');
    var field = el('textarea', 'field');
    field.id = 'paste-field';
    field.rows = 3;
    var paste = el('button', 'link-btn', 'Вставить из буфера');
    paste.type = 'button';
    wrap.appendChild(label);
    wrap.appendChild(field);
    wrap.appendChild(paste);
    var handle = dialog({
      title: 'Добавить по ссылке',
      body: wrap,
      ok: 'Добавить',
      onOk: function () {
        var text = field.value;
        api().ParseLink(text).then(function (link) {
          if (!link) { showMessage('Не получилось', 'В тексте нет ссылки на подключение.'); return; }
          closeAll();
          addLink(link);
        });
        return false;
      }
    });
    var check = function () { handle.ok.disabled = field.value.trim() === ''; };
    field.addEventListener('input', check);
    paste.addEventListener('click', function () {
      api().LinkFromClipboard().then(function (link) {
        if (link) { field.value = link.url; check(); }
      });
    });
    check();
    setTimeout(function () { field.focus(); }, 0);
  }

  function onAdd() {
    if (adding) { return; }
    api().LinkFromClipboard().then(function (link) {
      if (link) { addLink(link); } else { askPaste(); }
    });
  }

  // Ссылка с кнопки на сайте: сначала спрашиваем.
  function confirmLink(link) {
    if (!link) { return; }
    var text = (link.name ? '«' + link.name + '»\n' : '') + 'Сервер: ' + link.host;
    dialog({ title: 'Добавить подключение?', body: text, ok: 'Добавить', onOk: function () { addLink(link); } });
  }

  function refreshProfile(profile) {
    refreshingId = profile.id;
    render();
    api().Refresh(profile.id).then(function () {
      toast('Обновлено: ' + profile.name);
    }, function (error) {
      showMessage('Не получилось', String(error));
    }).then(function () {
      refreshingId = null;
      return refresh();
    });
  }

  function renameProfile(profile) {
    var wrap = el('div');
    var label = el('label', 'field-label', 'Название');
    label.setAttribute('for', 'rename-field');
    var field = el('input', 'field');
    field.id = 'rename-field';
    field.value = profile.name;
    wrap.appendChild(label);
    wrap.appendChild(field);
    var handle = dialog({
      title: 'Переименовать',
      body: wrap,
      ok: 'Сохранить',
      onOk: function () { call(api().Rename(profile.id, field.value)); }
    });
    field.addEventListener('input', function () { handle.ok.disabled = field.value.trim() === ''; });
    field.addEventListener('keydown', function (event) { if (event.key === 'Enter' && field.value.trim()) { handle.ok.click(); } });
    setTimeout(function () { field.focus(); field.select(); }, 0);
  }

  function removeProfile(profile) {
    dialog({
      title: 'Удалить подключение?',
      body: '«' + profile.name + '» будет удалено с этого компьютера. Его можно добавить заново по той же ссылке.',
      ok: 'Удалить',
      okClass: 'danger',
      onOk: function () { call(api().Remove(profile.id)); }
    });
  }

  // ---------- запуск ----------

  function start() {
    $('power').addEventListener('click', function () { call(api().Toggle()); });
    $('server-row').addEventListener('click', function () { showSheet('servers'); });
    $('add').addEventListener('click', onAdd);
    $('master').addEventListener('click', function () { showSheet('master'); });
    $('overlay').addEventListener('click', closeAll);
    Array.prototype.forEach.call(document.querySelectorAll('[data-close]'), function (button) { button.addEventListener('click', closeAll); });
    $('open-log').addEventListener('click', function () { closeAll(); call(api().OpenLog()); });
    $('open-folder').addEventListener('click', function () { closeAll(); call(api().OpenDataFolder()); });
    $('quit').addEventListener('click', function () { api().Quit(); });

    api().Version().then(function (version) { $('version').textContent = 'Версия ' + version; });
    window.runtime.EventsOn('link', confirmLink);
    refresh().then(function () { return api().TakePendingLink(); }).then(confirmLink);
    setInterval(refresh, 1000);
  }

  // Программа подключает свои скрипты до этого файла; на всякий случай ждём их.
  (function waitForBridge(attempt) {
    if (window.go && window.go.main && window.go.main.App && window.runtime) { start(); return; }
    if (attempt < 200) { setTimeout(function () { waitForBridge(attempt + 1); }, 25); }
  })(0);
})();
