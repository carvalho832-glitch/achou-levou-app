(() => {
  'use strict';

  const VERSION = '92.0.0';
  const DEFAULT_BOT_URL = 'http://localhost:3010';

  function panelUrl() {
    const config = window.AchouLevouBotQueue?.loadConfig?.() || {};
    const base = String(config.botUrl || DEFAULT_BOT_URL).trim().replace(/\/+$/, '');
    const target = new URL(`${base}/painel`, window.location.href);

    const localPanel = ['localhost', '127.0.0.1'].includes(target.hostname);
    if (target.protocol !== 'https:' && !(localPanel && target.protocol === 'http:')) {
      throw new Error('O endereço do Painel WhatsApp precisa usar HTTPS ou localhost.');
    }

    return target.toString();
  }

  function openInsideCurrentWebView() {
    const target = panelUrl();
    window.location.assign(target);
  }

  function install() {
    if (window.AchouLevouBotQueue) {
      window.AchouLevouBotQueue.openPanel = openInsideCurrentWebView;
    }

    const button = document.getElementById('btn-abrir-painel-bot');
    if (!button || button.dataset.embeddedPanelVersion === VERSION) return;

    button.dataset.embeddedPanelVersion = VERSION;
    button.title = 'Abrir dentro do Radar IA';
    button.addEventListener('click', event => {
      event.preventDefault();
      event.stopImmediatePropagation();

      try {
        openInsideCurrentWebView();
      } catch (error) {
        console.error('[PAINEL EMBUTIDO]', error);
        alert(error?.message || 'Não foi possível abrir o Painel WhatsApp dentro do Radar.');
      }
    }, true);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', install, { once: true });
  } else {
    install();
  }

  window.addEventListener('achoulevou:bot-profile', install);
  window.AchouLevouEmbeddedPanel = {
    version: VERSION,
    open: openInsideCurrentWebView,
    install
  };
})();
