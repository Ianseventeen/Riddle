// Leitor de QR code pela câmera traseira.
// Usa o BarcodeDetector nativo (Chrome/Android); nos demais (ex.: iPhone) carrega o jsQR.

const JSQR_URL = 'https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.min.js';

function carregarJsQR() {
  if (window.jsQR) return Promise.resolve(window.jsQR);
  return new Promise((ok, falha) => {
    const s = document.createElement('script');
    s.src = JSQR_URL;
    s.onload = () => ok(window.jsQR);
    s.onerror = () => falha(new Error('Não foi possível carregar o leitor de QR code.'));
    document.head.appendChild(s);
  });
}

async function criarDetector() {
  if ('BarcodeDetector' in window) {
    try {
      const formatos = await window.BarcodeDetector.getSupportedFormats();
      if (formatos.includes('qr_code')) {
        const det = new window.BarcodeDetector({ formats: ['qr_code'] });
        return async (video) => {
          const achados = await det.detect(video);
          return achados[0]?.rawValue ?? null;
        };
      }
    } catch { /* cai para o jsQR */ }
  }
  const jsQR = await carregarJsQR();
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  return async (video) => {
    const w = video.videoWidth, h = video.videoHeight;
    if (!w || !h) return null;
    const escala = Math.min(1, 640 / Math.max(w, h));
    canvas.width = Math.round(w * escala);
    canvas.height = Math.round(h * escala);
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
    return jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' })?.data ?? null;
  };
}

/** Extrai o código da sala de um QR do Riddles (URL com ?room=) ou de um código puro. */
export function codigoDoQr(texto) {
  if (!texto) return null;
  try {
    const url = new URL(texto);
    const room = url.searchParams.get('room');
    if (room) return room.trim().toUpperCase();
  } catch { /* não é URL */ }
  const puro = texto.trim().toUpperCase();
  return /^[A-Z0-9]{4,8}$/.test(puro) ? puro : null;
}

/**
 * Abre a câmera no diálogo e resolve com o código lido, ou null se cancelar.
 * @param {{dialogo: HTMLDialogElement, video: HTMLVideoElement, texto: HTMLElement, fechar: HTMLButtonElement}} ui
 */
export async function lerQrCode(ui) {
  const { dialogo, video, texto, fechar } = ui;
  texto.textContent = 'Aponte para o QR code da sala.';
  dialogo.showModal();

  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    texto.textContent = 'A câmera só funciona em conexão segura (https). Digite o código da sala.';
    await new Promise((ok) => { fechar.onclick = ok; dialogo.oncancel = ok; });
    dialogo.close();
    return null;
  }

  let stream;
  let ativo = true;
  const parar = () => {
    ativo = false;
    stream?.getTracks().forEach((t) => t.stop());
    video.srcObject = null;
    if (dialogo.open) dialogo.close();
  };

  return new Promise(async (resolver) => {
    fechar.onclick = () => { parar(); resolver(null); };
    dialogo.oncancel = (e) => { e.preventDefault(); parar(); resolver(null); };
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
      video.srcObject = stream;
      await video.play();
      const detectar = await criarDetector();
      const quadro = async () => {
        if (!ativo) return;
        try {
          const lido = await detectar(video);
          const codigo = codigoDoQr(lido);
          if (codigo) {
            navigator.vibrate?.(40);
            parar();
            resolver(codigo);
            return;
          }
          if (lido) texto.textContent = 'Esse QR code não é de uma sala do Riddles.';
        } catch { /* quadro ruim; tenta o próximo */ }
        setTimeout(quadro, 200);
      };
      quadro();
    } catch (e) {
      texto.textContent = e?.name === 'NotAllowedError'
        ? 'Sem permissão para usar a câmera. Libere nas configurações do navegador ou digite o código.'
        : 'Não foi possível abrir a câmera. Digite o código da sala.';
    }
  });
}
