// Conexão WebSocket com reconexão automática e "ping" para manter a conexão viva.

const PING_MS = 20_000;
const ESPERAS_MS = [500, 1000, 2000, 4000, 8000];

export class Conexao {
  /**
   * @param {(msg: object) => void} aoReceber  mensagem do servidor
   * @param {(estado: 'aberta' | 'caiu') => void} aoMudar  estado da conexão
   */
  constructor(aoReceber, aoMudar) {
    this.aoReceber = aoReceber;
    this.aoMudar = aoMudar;
    this.fila = [];
    this.tentativas = 0;
    this.ws = null;
    this.ping = null;
    this.abrir();

    // Ao voltar para o app (celular desbloqueado), confere a conexão na hora.
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') this.reabrirAgora();
    });
    window.addEventListener('online', () => this.reabrirAgora());
  }

  url() {
    const protocolo = location.protocol === 'https:' ? 'wss' : 'ws';
    return `${protocolo}://${location.host}/ws`;
  }

  aberta() {
    return this.ws && this.ws.readyState === WebSocket.OPEN;
  }

  abrir() {
    clearTimeout(this.timer);
    const ws = new WebSocket(this.url());
    this.ws = ws;
    ws.onopen = () => {
      this.tentativas = 0;
      this.aoMudar('aberta');
      for (const m of this.fila.splice(0)) ws.send(JSON.stringify(m));
      clearInterval(this.ping);
      this.ping = setInterval(() => this.enviar({ type: 'PING' }), PING_MS);
    };
    ws.onmessage = (e) => {
      let msg;
      try { msg = JSON.parse(e.data); } catch { return; }
      if (msg.type !== 'PONG') this.aoReceber(msg);
    };
    ws.onclose = () => {
      clearInterval(this.ping);
      if (this.ws !== ws) return;
      this.aoMudar('caiu');
      const espera = ESPERAS_MS[Math.min(this.tentativas++, ESPERAS_MS.length - 1)];
      this.timer = setTimeout(() => this.abrir(), espera);
    };
  }

  reabrirAgora() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) return;
    this.abrir();
  }

  /** Envia agora, ou assim que a conexão abrir. */
  enviar(msg) {
    if (this.aberta()) this.ws.send(JSON.stringify(msg));
    else if (msg.type !== 'PING') this.fila.push(msg);
  }
}
