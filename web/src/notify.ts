const KEY_DESKTOP = 'chatshy.notify.desktop';
const KEY_SOUND = 'chatshy.notify.sound';

export function desktopEnabled(): boolean {
  return localStorage.getItem(KEY_DESKTOP) !== '0';
}

export function soundEnabled(): boolean {
  return localStorage.getItem(KEY_SOUND) !== '0';
}

export function setDesktop(v: boolean): void {
  localStorage.setItem(KEY_DESKTOP, v ? '1' : '0');
  if (v) void requestDesktopPermission();
}

export function setSound(v: boolean): void {
  localStorage.setItem(KEY_SOUND, v ? '1' : '0');
}

export async function requestDesktopPermission(): Promise<boolean> {
  if (!('Notification' in window)) return false;
  if (Notification.permission === 'granted') return true;
  if (Notification.permission === 'denied') return false;
  const p = await Notification.requestPermission();
  return p === 'granted';
}

export function notifyNewRequest(title: string, body: string): void {
  if (desktopEnabled() && 'Notification' in window && Notification.permission === 'granted') {
    try {
      new Notification(title, { body, tag: 'chatshy-job' });
    } catch {
      /* 某些环境（如手机浏览器）不支持直接构造 */
    }
  }
  if (soundEnabled()) beep();
}

let audioCtx: AudioContext | null = null;

function beep(): void {
  try {
    audioCtx ??= new AudioContext();
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.type = 'sine';
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.08, audioCtx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.25);
    osc.connect(gain).connect(audioCtx.destination);
    osc.start();
    osc.stop(audioCtx.currentTime + 0.3);
  } catch {
    /* 音频不可用时忽略 */
  }
}

export function updateTitleBadge(pending: number): void {
  document.title = pending > 0 ? `(${pending}) ChatShy 操作台` : 'ChatShy 操作台';
}
