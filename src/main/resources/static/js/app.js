// Riddles — telas e estado do cliente. O servidor é a autoridade; aqui só se exibe e se envia.
import { Conexao } from './socket.js';
import { lerQrCode, codigoDoQr } from './scanner.js';

const $ = (sel) => document.querySelector(sel);
const NIVEIS = { normal: 'Normal', universitario: 'Universitário' };
const CHAVE_SESSAO = 'riddles.sessao';

const estado = {
  tela: 'home',
  sessao: lerSessao(),   // { room, token } para reconectar
  eu: null,              // meu playerId
  sala: null,            // última mensagem ROOM
  jogadores: [],
  questao: null,         // { id, category, grid|sequence, options }
  escolha: null,         // valor que enviei
  bloqueado: false,
  offset: 0,             // relógio do servidor - relógio local
  endsAt: 0,
  wakeLock: null,
};

// ------------------------------------------------------------------ sessão (só conveniência)

function lerSessao() {
  // sessionStorage: cada aba é um jogador; recarregar a aba reconecta na mesma sala.
  try { return JSON.parse(sessionStorage.getItem(CHAVE_SESSAO)); } catch { return null; }
}
function salvarSessao(s) {
  estado.sessao = s;
  try {
    if (s) sessionStorage.setItem(CHAVE_SESSAO, JSON.stringify(s));
    else sessionStorage.removeItem(CHAVE_SESSAO);
  } catch { /* navegação privada: segue sem reconexão após recarregar */ }
}

// ------------------------------------------------------------------ navegação

function mostrar(tela) {
  estado.tela = tela;
  document.querySelectorAll('[data-tela]').forEach((el) => { el.hidden = el.dataset.tela !== tela; });
  window.scrollTo(0, 0);
  if (tela !== 'jogo') pararRelogio();
}

document.querySelectorAll('[data-ir]').forEach((b) => b.addEventListener('click', () => {
  limparErros();
  mostrar(b.dataset.ir);
  const primeiro = document.querySelector(`[data-tela="${b.dataset.ir}"] input:not([type=radio])`);
  primeiro?.focus();
}));

function limparErros() {
  document.querySelectorAll('.erro-form').forEach((e) => { e.textContent = ''; });
}

// ------------------------------------------------------------------ conexão

const conexao = new Conexao(receber, (s) => {
  $('#conexao').hidden = s === 'aberta' || estado.tela === 'home';
  if (s === 'aberta' && estado.sessao) {
    conexao.enviar({ type: 'RESUME', room: estado.sessao.room, token: estado.sessao.token });
  }
});

function receber(msg) {
  switch (msg.type) {
    case 'JOINED': return aoEntrar(msg);
    case 'ROOM': return aoAtualizarSala(msg);
    case 'QUESTION': return aoReceberPergunta(msg);
    case 'WRONG': return aoErrar(msg);
    case 'LOCKED': return aoBloquear(msg);
    case 'REVEAL': return aoRevelar(msg);
    case 'GAME_OVER': return aoTerminar(msg);
    case 'ERROR': return aoErro(msg);
  }
}

function sincronizarRelogio(serverNow) {
  if (typeof serverNow === 'number') estado.offset = serverNow - Date.now();
}

// ------------------------------------------------------------------ criar e entrar

$('#form-criar').addEventListener('submit', (e) => {
  e.preventDefault();
  const f = e.currentTarget;
  const nickname = f.nickname.value.trim();
  if (!nickname) return erroNoForm(f, 'Escolha um apelido.');
  limparErros();
  conexao.enviar({ type: 'CREATE', nickname, level: f.level.value });
});

$('#form-entrar').addEventListener('submit', (e) => {
  e.preventDefault();
  const f = e.currentTarget;
  const room = f.room.value.trim().toUpperCase();
  const nickname = f.nickname.value.trim();
  if (room.length < 4) return erroNoForm(f, 'Digite o código da sala.');
  if (!nickname) return erroNoForm(f, 'Escolha um apelido.');
  limparErros();
  conexao.enviar({ type: 'JOIN', room, nickname });
});

$('#codigo').addEventListener('input', (e) => {
  e.target.value = e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '');
});

$('#abrir-camera').addEventListener('click', async () => {
  const codigo = await lerQrCode({ dialogo: $('#leitor'), video: $('#leitor-video'), texto: $('#leitor-texto'), fechar: $('#leitor-fechar') });
  if (!codigo) return;
  $('#codigo').value = codigo;
  const apelido = $('#form-entrar').nickname;
  if (apelido.value.trim()) $('#form-entrar').requestSubmit();
  else apelido.focus();
});

function erroNoForm(form, texto) {
  form.querySelector('.erro-form').textContent = texto;
}

function aoEntrar(msg) {
  estado.eu = msg.playerId;
  salvarSessao({ room: msg.roomCode, token: msg.token });
  if (location.search) history.replaceState(null, '', location.pathname);
  if (!msg.resumed) mostrar('lobby');
}

// ------------------------------------------------------------------ lobby

function aoAtualizarSala(msg) {
  estado.sala = msg;
  estado.jogadores = msg.players;
  if (msg.state === 'LOBBY') {
    desenharLobby();
    if (estado.tela !== 'lobby') mostrar('lobby');
  } else if (msg.state === 'PLAYING' || msg.state === 'REVEAL') {
    desenharPlacar();
  } else if (msg.state === 'FINISHED') {
    desenharBotoesResultado();
  }
}

function desenharLobby() {
  const sala = estado.sala;
  $('#lobby-codigo').textContent = sala.roomCode;
  $('#lobby-nivel').textContent = `${NIVEIS[sala.level]}, partida de ${sala.durationSeconds} segundos`;
  $('#lobby-contagem').textContent = `${sala.players.length} de ${sala.maxPlayers}`;
  desenharQr(sala.roomCode);

  const lista = $('#lobby-jogadores');
  lista.replaceChildren(...sala.players.map((p) => {
    const li = document.createElement('li');
    li.classList.toggle('voce', p.id === estado.eu);
    li.classList.toggle('offline', !p.connected);
    li.append(bolinha(p.color), span('nome', p.nickname));
    const tags = [];
    if (p.id === estado.eu) tags.push('você');
    if (p.isHost) tags.push('host');
    if (!p.connected) tags.push('reconectando');
    if (tags.length) li.append(span('tag', tags.join(', ')));
    return li;
  }));

  const souHost = sala.hostId === estado.eu;
  const comecar = $('#comecar');
  comecar.hidden = !souHost;
  comecar.disabled = sala.players.length < sala.minPlayers;
  const host = sala.players.find((p) => p.isHost);
  $('#lobby-dica').textContent = souHost
    ? (sala.players.length < sala.minPlayers ? `Esperando mais jogadores. Mínimo de ${sala.minPlayers}.` : 'Só você, como host, pode começar.')
    : `Aguardando ${host ? host.nickname : 'o host'} começar a partida.`;
}

let qrDesenhado = null;
function desenharQr(codigo) {
  if (qrDesenhado === codigo) return;
  const alvo = $('#lobby-qr');
  const url = `${location.origin}/?room=${codigo}`;
  if (typeof window.qrcode !== 'function') {
    alvo.textContent = '';
    return;
  }
  const qr = window.qrcode(0, 'M');
  qr.addData(url);
  qr.make();
  alvo.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 0, scalable: true });
  qrDesenhado = codigo;
}

$('#comecar').addEventListener('click', () => conexao.enviar({ type: 'START' }));

$('#compartilhar').addEventListener('click', async () => {
  const codigo = estado.sala?.roomCode;
  if (!codigo) return;
  const url = `${location.origin}/?room=${codigo}`;
  const botao = $('#compartilhar');
  try {
    if (navigator.share) {
      await navigator.share({ title: 'Riddles', text: `Entre na minha sala do Riddles. Código: ${codigo}`, url });
      return;
    }
    await navigator.clipboard.writeText(url);
    botao.textContent = 'Link copiado';
  } catch {
    botao.textContent = `Código: ${codigo}`;
  }
  setTimeout(() => { botao.textContent = 'Compartilhar convite'; }, 2500);
});

function sair() {
  conexao.enviar({ type: 'LEAVE' });
  salvarSessao(null);
  estado.eu = null;
  estado.sala = null;
  qrDesenhado = null;
  liberarTela();
  mostrar('home');
}
$('#sair-lobby').addEventListener('click', sair);
$('#sair-resultado').addEventListener('click', sair);

// ------------------------------------------------------------------ pergunta

function aoReceberPergunta(msg) {
  sincronizarRelogio(msg.serverNow);
  estado.endsAt = msg.endsAt;
  estado.questao = msg.question;
  estado.escolha = null;
  estado.vencedor = null;
  estado.bloqueado = !!msg.locked;

  $('#jogo-numero').textContent = `Pergunta ${msg.number}`;
  desenharQuestao(msg.question);
  desenharAlternativas(msg.question.options);
  $('#jogo-aviso').className = 'aviso';
  $('#jogo-aviso').replaceChildren();
  aoBloquear({ lockedIds: msg.lockedIds || [] });
  if (estado.bloqueado) avisoBloqueado(null);

  if (estado.tela !== 'jogo') mostrar('jogo');
  iniciarRelogio();
  manterTelaLigada();
}

function desenharQuestao(q, revelada) {
  const tabua = $('#jogo-tabua');
  const valores = q.category === 'matriz' ? q.grid.flat() : q.sequence;
  const grade = document.createElement('div');
  grade.className = q.category === 'matriz' ? 'matriz' : 'sequencia';
  for (const v of valores) {
    const cel = document.createElement('div');
    cel.className = 'celula';
    const val = document.createElement('span');
    val.className = 'valor';
    if (v == null) {
      if (revelada == null) {
        val.classList.add('vazia');
        val.textContent = '?';
        val.setAttribute('aria-label', 'número que falta');
      } else {
        val.classList.add('revelada');
        val.textContent = revelada;
      }
    } else {
      val.textContent = v;
    }
    cel.append(val);
    grade.append(cel);
  }
  tabua.replaceChildren(grade);
  tabua.setAttribute('aria-label', q.category === 'matriz' ? 'Matriz 3 por 3' : 'Sequência');
  $('#jogo-enunciado').textContent = q.category === 'matriz'
    ? 'Qual número completa a matriz?'
    : 'Qual é o próximo número da sequência?';
}

function desenharAlternativas(opcoes) {
  $('#jogo-alternativas').replaceChildren(...opcoes.map((v) => {
    const b = document.createElement('button');
    b.className = 'opcao';
    b.textContent = v;
    b.dataset.valor = v;
    b.disabled = estado.bloqueado;
    if (estado.bloqueado) b.classList.add('apagada');
    b.addEventListener('click', () => responder(v, b));
    return b;
  }));
}

function responder(valor, botao) {
  if (estado.bloqueado || estado.escolha != null || !estado.questao) return;
  estado.escolha = valor;
  botao.classList.add('enviando');
  document.querySelectorAll('.opcao').forEach((b) => { b.disabled = true; });
  conexao.enviar({ type: 'ANSWER', questionId: estado.questao.id, value: valor });
}

function aoErrar(msg) {
  if (msg.questionId !== estado.questao?.id) return;
  estado.bloqueado = true;
  document.querySelectorAll('.opcao').forEach((b) => {
    b.classList.remove('enviando');
    b.disabled = true;
    b.classList.add(Number(b.dataset.valor) === estado.escolha ? 'errada' : 'apagada');
  });
  avisoBloqueado(estado.escolha);
  navigator.vibrate?.([80, 60, 80]);
}

function avisoBloqueado(valor) {
  const aviso = $('#jogo-aviso');
  aviso.className = 'aviso erro';
  aviso.replaceChildren(
    strong(valor != null ? `Não é ${valor}. Você está fora desta pergunta.` : 'Você está fora desta pergunta.'),
    small('Aguarde os outros responderem.'),
  );
}

function aoBloquear(msg) {
  estado.bloqueados = new Set(msg.lockedIds);
  desenharPlacar();
}

function aoRevelar(msg) {
  if (msg.questionId !== estado.questao?.id) return;
  sincronizarRelogio(msg.serverNow);
  estado.jogadores = msg.players;
  estado.bloqueados = new Set();
  estado.vencedor = msg.winnerId;
  desenharPlacar();

  desenharQuestao(estado.questao, msg.answer);
  document.querySelectorAll('.opcao').forEach((b) => {
    b.disabled = true;
    b.classList.remove('enviando');
    const v = Number(b.dataset.valor);
    if (v === msg.answer) { b.classList.remove('apagada'); b.classList.add('certa'); }
    else if (!b.classList.contains('errada')) b.classList.add('apagada');
  });

  const vencedor = estado.jogadores.find((p) => p.id === msg.winnerId);
  const aviso = $('#jogo-aviso');
  aviso.className = 'aviso vitoria';
  const titulo = !vencedor ? `Ninguém acertou. Era ${msg.answer}.`
    : vencedor.id === estado.eu ? `Você acertou: ${msg.answer}`
    : `${vencedor.nickname} acertou: ${msg.answer}`;
  aviso.replaceChildren(strong(titulo), small(msg.rule));
  if (vencedor?.id === estado.eu) navigator.vibrate?.(60);
}

function desenharPlacar() {
  const ordenados = [...estado.jogadores].sort((a, b) => b.score - a.score);
  const bloqueados = estado.bloqueados || new Set();
  $('#jogo-placar').replaceChildren(...ordenados.map((p) => {
    const li = document.createElement('li');
    li.classList.toggle('voce', p.id === estado.eu);
    li.classList.toggle('bloqueado', bloqueados.has(p.id) || !p.connected);
    li.classList.toggle('ganhou', p.id === estado.vencedor);
    const pts = document.createElement('b');
    pts.textContent = p.score;
    li.append(bolinha(p.color), document.createTextNode(p.id === estado.eu ? 'Você' : p.nickname), pts);
    return li;
  }));
  const pos = ordenados.findIndex((p) => p.id === estado.eu);
  if (pos >= 0 && estado.questao) {
    const n = $('#jogo-numero').textContent.split(',')[0];
    $('#jogo-numero').textContent = `${n}, você está em ${pos + 1}º`;
  }
}

// ------------------------------------------------------------------ relógio

let raf = 0;
function iniciarRelogio() {
  cancelAnimationFrame(raf);
  const duracao = (estado.sala?.durationSeconds ?? 60) * 1000;
  const barra = $('#jogo-barra');
  const seg = $('#jogo-segundos');
  const passo = () => {
    const resta = Math.max(0, estado.endsAt - (Date.now() + estado.offset));
    const s = Math.ceil(resta / 1000);
    if (seg.textContent !== String(s)) seg.textContent = s;
    seg.classList.toggle('acabando', s <= 10);
    barra.style.transform = `scaleX(${Math.min(1, resta / duracao)})`;
    if (resta > 0) raf = requestAnimationFrame(passo);
  };
  passo();
}
function pararRelogio() { cancelAnimationFrame(raf); }

// ------------------------------------------------------------------ resultado

function aoTerminar(msg) {
  estado.questao = null;
  liberarTela();
  const ranking = msg.ranking;
  const segundos = estado.sala?.durationSeconds ?? 60;
  $('#resultado-resumo').textContent = `${msg.questionsPlayed} ${msg.questionsPlayed === 1 ? 'pergunta' : 'perguntas'} em ${segundos} segundos`;

  const podio = $('#resultado-podio');
  const ordem = [1, 0, 2].filter((i) => ranking[i]);
  podio.replaceChildren(...ordem.map((i) => {
    const p = ranking[i];
    const col = document.createElement('div');
    col.className = 'degrau-col';
    const nome = document.createElement('div');
    nome.className = 'degrau-nome';
    nome.append(bolinha(p.color), span('', p.id === estado.eu ? 'Você' : p.nickname));
    const pts = span('degrau-pontos', `${p.score} ${p.score === 1 ? 'ponto' : 'pontos'}`);
    const degrau = document.createElement('div');
    degrau.className = `degrau p${Math.min(p.position, 3)}`; // empate: mesmo degrau
    degrau.textContent = `${p.position}º`;
    col.append(nome, pts, degrau);
    return col;
  }));

  $('#resultado-demais').replaceChildren(...ranking.slice(3).map((p) => {
    const li = document.createElement('li');
    li.append(span('pos', `${p.position}º`), bolinha(p.color), span('nome', p.id === estado.eu ? 'Você' : p.nickname), span('pts', String(p.score)));
    return li;
  }));

  desenharBotoesResultado();
  mostrar('resultado');
}

function desenharBotoesResultado() {
  const souHost = estado.sala?.hostId === estado.eu;
  $('#jogar-de-novo').hidden = !souHost;
  $('#resultado-dica').textContent = souHost
    ? 'Jogar de novo volta todos ao lobby com o placar zerado.'
    : 'Aguardando o host decidir se vão jogar de novo.';
}

$('#jogar-de-novo').addEventListener('click', () => conexao.enviar({ type: 'REMATCH' }));

// ------------------------------------------------------------------ erros

const MENSAGEM_FORM = new Set(['INVALID_NICKNAME', 'NICK_TAKEN', 'ROOM_NOT_FOUND', 'ROOM_FULL', 'GAME_IN_PROGRESS', 'INVALID_LEVEL', 'SERVER_FULL']);

function aoErro(msg) {
  if (msg.code === 'RESUME_FAILED') {
    salvarSessao(null);
    if (estado.tela !== 'criar' && estado.tela !== 'entrar') mostrar('home');
    return;
  }
  if (MENSAGEM_FORM.has(msg.code)) {
    const form = estado.tela === 'criar' ? $('#form-criar') : $('#form-entrar');
    if (estado.tela !== 'criar' && estado.tela !== 'entrar') mostrar('entrar');
    return erroNoForm(form, msg.message);
  }
  if (estado.tela === 'jogo' && (msg.code === 'INVALID_OPTION' || msg.code === 'LOCKED')) {
    estado.escolha = null;
    document.querySelectorAll('.opcao').forEach((b) => { b.classList.remove('enviando'); b.disabled = estado.bloqueado; });
    return;
  }
  if (estado.tela === 'lobby') $('#lobby-dica').textContent = msg.message;
}

// ------------------------------------------------------------------ celular: tela ligada

async function manterTelaLigada() {
  if (estado.wakeLock || !('wakeLock' in navigator)) return;
  try {
    estado.wakeLock = await navigator.wakeLock.request('screen');
    estado.wakeLock.addEventListener('release', () => { estado.wakeLock = null; });
  } catch { /* sem permissão ou bateria baixa */ }
}
function liberarTela() {
  estado.wakeLock?.release().catch(() => {});
  estado.wakeLock = null;
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && estado.tela === 'jogo') manterTelaLigada();
});

// ------------------------------------------------------------------ utilitários de DOM

function span(classe, texto) {
  const s = document.createElement('span');
  if (classe) s.className = classe;
  s.textContent = texto;
  return s;
}
function strong(texto) { const s = document.createElement('strong'); s.textContent = texto; return s; }
function small(texto) { const s = document.createElement('small'); s.textContent = texto; return s; }
function bolinha(cor) {
  const b = document.createElement('span');
  b.className = 'bolinha';
  b.style.setProperty('--cor', `var(--j${cor})`);
  return b;
}

// ------------------------------------------------------------------ início

const salaNaUrl = codigoDoQr(location.href);
if (salaNaUrl) {
  $('#codigo').value = salaNaUrl;
  mostrar('entrar');
  $('#form-entrar').nickname.focus();
} else {
  mostrar('home');
}
