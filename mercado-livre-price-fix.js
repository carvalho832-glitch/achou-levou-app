(() => {
  'use strict';

  const inputLink = document.getElementById('input-link');
  const btnPuxar = document.getElementById('btn-puxar');
  const displayProduto = document.getElementById('display-produto');
  const displayDe = document.getElementById('display-de');
  const displayPor = document.getElementById('display-por');
  const loader = document.getElementById('loader-global');

  if (!inputLink || !btnPuxar || !displayProduto || !displayDe || !displayPor) return;

  function extrairLink(texto = '') {
    return String(texto || '').match(/https?:\/\/[^\s]+/)?.[0] || String(texto || '').trim();
  }

  function isMercadoLivre(texto = '') {
    const link = String(texto || '').toLowerCase();
    return link.includes('mercadolivre') || link.includes('mercado livre') || link.includes('meli.la');
  }

  function limparTituloProduto(produto = '') {
    return String(produto || '')
      .replace(/\|\s?Mercado\s?Livre.*$/gi, '')
      .replace(/- Mercado Livre.*$/gi, '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function numeroMoeda(valor) {
    if (valor === null || valor === undefined || valor === '') return null;
    if (typeof valor === 'number') return Number.isFinite(valor) ? valor : null;

    let texto = String(valor).trim().replace(/[^\d,.-]/g, '');
    if (!texto) return null;

    const ultimaVirgula = texto.lastIndexOf(',');
    const ultimoPonto = texto.lastIndexOf('.');

    if (ultimaVirgula > ultimoPonto) {
      texto = texto.replace(/\./g, '').replace(',', '.');
    } else if (ultimoPonto > ultimaVirgula && ultimaVirgula >= 0) {
      texto = texto.replace(/,/g, '');
    } else if (ultimaVirgula >= 0) {
      texto = texto.replace(',', '.');
    }

    const numero = Number(texto);
    return Number.isFinite(numero) ? numero : null;
  }

  function formatarBRL(valor) {
    const numero = numeroMoeda(valor);
    if (numero === null || numero <= 0) return '';
    return numero.toLocaleString('pt-BR', {
      style: 'currency',
      currency: 'BRL',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  function moedaPorPartes(reais, centavos) {
    const inteiroTexto = String(reais ?? '').replace(/\D/g, '');
    if (!inteiroTexto) return '';

    const inteiro = Number(inteiroTexto);
    if (!Number.isFinite(inteiro) || inteiro <= 0) return '';

    let cents = String(centavos ?? '').replace(/\D/g, '');
    if (!cents) cents = '00';
    if (cents.length === 1) cents = cents.padStart(2, '0');
    cents = cents.slice(0, 2);

    return formatarBRL(inteiro + Number(cents) / 100);
  }

  function montarConsulta(link) {
    const params = new URLSearchParams();
    params.set('url', link);
    params.set('prerender', 'true');

    // Preço atual: prioriza o valor estruturado do bloco principal #price.
    params.set('data.ml_por.selector', '#price .ui-pdp-price__second-line meta[itemprop="price"], #price meta[itemprop="price"]');
    params.set('data.ml_por.attr', 'content');
    params.set('data.ml_por.type', 'number');

    // Fallback visual, também limitado ao bloco principal do produto.
    params.set('data.ml_por_r.selector', '#price .ui-pdp-price__second-line .andes-money-amount__fraction');
    params.set('data.ml_por_c.selector', '#price .ui-pdp-price__second-line .andes-money-amount__cents');

    // Preço anterior: nunca procurar fora do #price, evitando cards/recomendações.
    params.set('data.ml_de_r.selector', '#price .andes-money-amount--previous .andes-money-amount__fraction, #price .ui-pdp-price__original-value .andes-money-amount__fraction');
    params.set('data.ml_de_c.selector', '#price .andes-money-amount--previous .andes-money-amount__cents, #price .ui-pdp-price__original-value .andes-money-amount__cents');

    return `https://api.microlink.io?${params.toString()}`;
  }

  async function puxarMercadoLivre(link) {
    const resposta = await fetch(montarConsulta(link), {
      method: 'GET',
      headers: { Accept: 'application/json' },
      cache: 'no-store'
    });

    const json = await resposta.json().catch(() => null);
    if (!resposta.ok || !json?.data) {
      throw new Error(json?.message || 'A consulta do Mercado Livre não retornou dados.');
    }

    const dados = json.data;
    const precoAtual = formatarBRL(dados.ml_por) || moedaPorPartes(dados.ml_por_r, dados.ml_por_c);
    let precoAnterior = moedaPorPartes(dados.ml_de_r, dados.ml_de_c);

    if (!precoAtual) {
      throw new Error('Não encontrei o preço principal desse anúncio do Mercado Livre.');
    }

    const atualNumero = numeroMoeda(precoAtual);
    const anteriorNumero = numeroMoeda(precoAnterior);

    // Se o suposto preço anterior não for realmente maior, não mostrar um valor enganoso.
    if (anteriorNumero === null || atualNumero === null || anteriorNumero <= atualNumero) {
      precoAnterior = '';
    }

    return {
      titulo: limparTituloProduto(dados.title || ''),
      precoAtual,
      precoAnterior,
      finalUrl: dados.url || link
    };
  }

  document.addEventListener('click', async event => {
    const alvo = event.target.closest?.('#btn-puxar');
    if (!alvo || !isMercadoLivre(inputLink.value)) return;

    // Impede o leitor antigo de executar em paralelo e capturar preços de cards relacionados.
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();

    const link = extrairLink(inputLink.value);
    if (!link) return alert('Cole o link do Mercado Livre.');

    btnPuxar.disabled = true;
    btnPuxar.innerText = '🔄 Conferindo Mercado Livre...';
    if (loader) loader.style.display = 'flex';
    displayProduto.value = 'Buscando...';
    displayDe.value = '';
    displayPor.value = '';

    try {
      const dados = await puxarMercadoLivre(link);
      displayProduto.value = dados.titulo || 'Produto Mercado Livre';
      displayDe.value = dados.precoAnterior;
      displayPor.value = dados.precoAtual;
      window.__achouLevouMercadoLivreResolvedUrl = dados.finalUrl;
      console.log('[Mercado Livre] preço principal conferido:', dados);
    } catch (erro) {
      console.error('[Mercado Livre] falha na leitura do preço principal:', erro);
      displayProduto.value = '';
      displayDe.value = '';
      displayPor.value = '';
      alert(`Não consegui confirmar o preço principal do Mercado Livre.\n\n${erro.message || erro}\n\nO link de afiliado foi preservado. Tente novamente ou preencha o preço manualmente.`);
    } finally {
      if (loader) loader.style.display = 'none';
      btnPuxar.disabled = false;
      btnPuxar.innerText = '🔎 Puxar produto';
    }
  }, true);

  console.log('Correção de preços do Mercado Livre ativada. v1.0.0');
})();
