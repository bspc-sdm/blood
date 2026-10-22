// কলের শব্দ-ছবি (LiveKit, livekit-client) — দরকার হলে তবেই আলাদা অংশে লোড হয় (প্রথম পাতা ভারী হয় না)।
// সার্ভার যে `url` আর `token` দেয় সেখানেই যুক্ত হয় (ঠিকানা এখানে লেখা নেই)।
// পরীক্ষার বিল্ডে window.__lfTest.fakeMedia থাকলে আসল LiveKit-এর বদলে নকল (Playwright নকল API-র সাথে)।
import { TEST_MODE } from '@/lib/testmode';

export type MediaStatus = 'connecting' | 'connected' | 'reconnecting' | 'disconnected';
/** মাইক/ক্যামেরা চালু না হওয়ার কারণ */
export type MediaFail = 'denied' | 'nodevice' | 'unsupported' | 'failed';
export class MediaError extends Error {
  constructor(public kind: MediaFail, message: string = kind) {
    super(message);
  }
}
export interface MediaEvents {
  status: (s: MediaStatus) => void;
  /** অন্যজন ঘরে আছেন কি না */
  peer: (present: boolean) => void;
  /** অন্যজনের ভিডিও আসছে কি না */
  remoteVideo: (on: boolean) => void;
  /** নেট দুর্বল (নিজের বা অন্যজনের) */
  poor: (poor: boolean) => void;
  /** ব্রাউজার শব্দ চালাতে দিচ্ছে না (একবার চাপ লাগবে) */
  audioBlocked: (blocked: boolean) => void;
}
export interface DeviceLists { audioinput: MediaDeviceInfo[]; videoinput: MediaDeviceInfo[]; audiooutput: MediaDeviceInfo[] }
export interface CallMedia {
  connect(url: string, token: string): Promise<void>;
  setMic(on: boolean): Promise<void>;
  setCam(on: boolean): Promise<void>;
  flip(): Promise<void>;
  devices(): Promise<DeviceLists>;
  useDevice(kind: MediaDeviceKind, id: string): Promise<void>;
  /** শুধু অডিও: নিজের ক্যামেরা বন্ধ আর অন্যজনের ভিডিও নেওয়া বন্ধ */
  setAudioOnly(on: boolean): Promise<void>;
  attachLocal(el: HTMLVideoElement | null): void;
  attachRemote(el: HTMLVideoElement | null): void;
  startAudio(): Promise<void>;
  disconnect(): void;
}

/** getUserMedia-র ভুল থেকে কারণ */
export function failOf(e: unknown): MediaFail {
  const name = (e as { name?: string } | null)?.name ?? '';
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError') return 'denied';
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError' || name === 'OverconstrainedError') return 'nodevice';
  return 'failed';
}
export const mediaSupported = () => typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia && typeof RTCPeerConnection !== 'undefined';

export async function createMedia(ev: MediaEvents): Promise<CallMedia> {
  if (TEST_MODE && (window as any).__lfTest?.fakeMedia) return fakeMedia(ev, (window as any).__lfTest.fakeMedia);
  if (!mediaSupported()) throw new MediaError('unsupported');
  const lk = await import('livekit-client');
  return liveKitMedia(lk, ev);
}

type LK = typeof import('livekit-client');

function liveKitMedia(lk: LK, ev: MediaEvents): CallMedia {
  const { Room, RoomEvent, Track, ConnectionState, ConnectionQuality, VideoPresets } = lk;
  const room = new Room({
    adaptiveStream: true,
    dynacast: true,
    videoCaptureDefaults: { resolution: VideoPresets.h540.resolution, facingMode: 'user' },
    audioCaptureDefaults: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    publishDefaults: { simulcast: true, videoCodec: 'vp8' },
  });
  const audioEls = new Set<HTMLMediaElement>();
  let remoteVideoTrack: import('livekit-client').RemoteTrack | null = null;
  let remoteEl: HTMLVideoElement | null = null;
  let localEl: HTMLVideoElement | null = null;
  let facing: 'user' | 'environment' = 'user';
  let audioOnly = false;
  const poorSet = new Set<string>();

  const others = () => room.remoteParticipants.size;
  const localCamTrack = () => room.localParticipant.getTrackPublication(Track.Source.Camera)?.track ?? null;
  const reattachLocal = () => {
    const t = localCamTrack();
    if (t && localEl) t.attach(localEl);
  };
  const setRemoteVideo = (t: typeof remoteVideoTrack) => {
    if (remoteVideoTrack && remoteEl) remoteVideoTrack.detach(remoteEl);
    remoteVideoTrack = t;
    if (t && remoteEl) t.attach(remoteEl);
    ev.remoteVideo(!!t);
  };

  room
    .on(RoomEvent.TrackSubscribed, (track) => {
      if (track.kind === Track.Kind.Audio) {
        const el = track.attach();
        el.setAttribute('data-call-audio', '');
        el.style.display = 'none';
        document.body.appendChild(el);
        audioEls.add(el);
      } else if (track.kind === Track.Kind.Video) setRemoteVideo(track);
    })
    .on(RoomEvent.TrackUnsubscribed, (track) => {
      if (track.kind === Track.Kind.Audio) {
        for (const el of track.detach()) {
          el.remove();
          audioEls.delete(el);
        }
      } else if (track === remoteVideoTrack) setRemoteVideo(null);
    })
    .on(RoomEvent.TrackMuted, (pub, p) => {
      if (p !== room.localParticipant && pub.kind === Track.Kind.Video) ev.remoteVideo(false);
    })
    .on(RoomEvent.TrackUnmuted, (pub, p) => {
      if (p !== room.localParticipant && pub.kind === Track.Kind.Video && remoteVideoTrack) ev.remoteVideo(true);
    })
    .on(RoomEvent.LocalTrackPublished, () => reattachLocal())
    .on(RoomEvent.ParticipantConnected, () => ev.peer(true))
    .on(RoomEvent.ParticipantDisconnected, () => ev.peer(others() > 0))
    .on(RoomEvent.Reconnecting, () => ev.status('reconnecting'))
    .on(RoomEvent.SignalReconnecting, () => ev.status('reconnecting'))
    .on(RoomEvent.Reconnected, () => ev.status('connected'))
    .on(RoomEvent.Disconnected, () => ev.status('disconnected'))
    .on(RoomEvent.AudioPlaybackStatusChanged, () => ev.audioBlocked(!room.canPlaybackAudio))
    .on(RoomEvent.ConnectionQualityChanged, (q, p) => {
      if (q === ConnectionQuality.Poor || q === ConnectionQuality.Lost) poorSet.add(p.identity);
      else poorSet.delete(p.identity);
      ev.poor(poorSet.size > 0);
    })
    .on(RoomEvent.ConnectionStateChanged, (s) => {
      if (s === ConnectionState.Connected) ev.status('connected');
    });

  return {
    async connect(url, token) {
      ev.status('connecting');
      await room.connect(url, token, { autoSubscribe: true });
      ev.status('connected');
      ev.peer(others() > 0);
      ev.audioBlocked(!room.canPlaybackAudio);
    },
    async setMic(on) {
      try {
        await room.localParticipant.setMicrophoneEnabled(on);
      } catch (e) {
        throw new MediaError(failOf(e), String((e as Error)?.message ?? e));
      }
    },
    async setCam(on) {
      try {
        await room.localParticipant.setCameraEnabled(on, { facingMode: facing });
        reattachLocal();
      } catch (e) {
        throw new MediaError(failOf(e), String((e as Error)?.message ?? e));
      }
    },
    async flip() {
      const t = localCamTrack() as import('livekit-client').LocalVideoTrack | null;
      if (!t) return;
      const list = await Room.getLocalDevices('videoinput', false).catch(() => [] as MediaDeviceInfo[]);
      if (list.length > 2 || !('ontouchstart' in window)) {
        // কম্পিউটারে (বা অনেক ক্যামেরা): পরের ক্যামেরা
        const cur = room.getActiveDevice('videoinput');
        const i = list.findIndex((d) => d.deviceId === cur);
        const next = list[(i + 1) % Math.max(1, list.length)];
        if (next) await room.switchActiveDevice('videoinput', next.deviceId);
      } else {
        // ফোনে: সামনের ↔ পেছনের
        facing = facing === 'user' ? 'environment' : 'user';
        await t.restartTrack({ facingMode: facing });
      }
      reattachLocal();
    },
    async devices() {
      const get = (k: MediaDeviceKind) => Room.getLocalDevices(k, false).catch(() => [] as MediaDeviceInfo[]);
      const [a, v, o] = await Promise.all([get('audioinput'), get('videoinput'), get('audiooutput')]);
      return { audioinput: a, videoinput: v, audiooutput: o };
    },
    async useDevice(kind, id) {
      await room.switchActiveDevice(kind, id);
      if (kind === 'videoinput') reattachLocal();
    },
    async setAudioOnly(on) {
      audioOnly = on;
      if (on) await room.localParticipant.setCameraEnabled(false);
      for (const p of room.remoteParticipants.values()) {
        for (const pub of p.videoTrackPublications.values()) pub.setSubscribed(!audioOnly);
      }
    },
    attachLocal(el) {
      if (localEl && localEl !== el) localCamTrack()?.detach(localEl);
      localEl = el;
      reattachLocal();
    },
    attachRemote(el) {
      if (remoteEl && remoteVideoTrack && remoteEl !== el) remoteVideoTrack.detach(remoteEl);
      remoteEl = el;
      if (el && remoteVideoTrack) remoteVideoTrack.attach(el);
    },
    async startAudio() {
      await room.startAudio();
      ev.audioBlocked(!room.canPlaybackAudio);
    },
    disconnect() {
      for (const el of audioEls) el.remove();
      audioEls.clear();
      void room.disconnect(true);
    },
  };
}

/** নকল (শুধু পরীক্ষায়): যুক্ত হয়, কিছুক্ষণ পর অন্যজন আসেন; window.__lfCallFake দিয়ে পরীক্ষা ঘটনা ঘটায় */
function fakeMedia(ev: MediaEvents, opts: { peerAfterMs?: number; denyMic?: boolean; denyCam?: boolean; failConnect?: boolean }): CallMedia {
  const log: string[] = [];
  const timers: ReturnType<typeof setTimeout>[] = [];
  (window as any).__lfCallFake = {
    log,
    peerJoin: () => ev.peer(true),
    peerLeave: () => ev.peer(false),
    reconnecting: () => ev.status('reconnecting'),
    reconnected: () => ev.status('connected'),
    poor: (p: boolean) => ev.poor(p),
    drop: () => ev.status('disconnected'),
  };
  return {
    async connect(url, token) {
      log.push(`connect ${url} ${token}`);
      ev.status('connecting');
      await new Promise((r) => setTimeout(r, 30));
      if (opts.failConnect) throw new Error('could not establish signal connection');
      ev.status('connected');
      if (opts.peerAfterMs != null && opts.peerAfterMs >= 0) timers.push(setTimeout(() => ev.peer(true), opts.peerAfterMs));
    },
    async setMic(on) {
      log.push(`mic ${on}`);
      if (on && opts.denyMic) throw new MediaError('denied');
    },
    async setCam(on) {
      log.push(`cam ${on}`);
      if (on && opts.denyCam) throw new MediaError('denied');
    },
    async flip() {
      log.push('flip');
    },
    async devices() {
      const d = (kind: MediaDeviceKind, id: string, label: string) => ({ kind, deviceId: id, label, groupId: '' }) as MediaDeviceInfo;
      return { audioinput: [d('audioinput', 'm1', 'Mic 1'), d('audioinput', 'm2', 'Mic 2')], videoinput: [d('videoinput', 'c1', 'Cam 1')], audiooutput: [] };
    },
    async useDevice(kind, id) {
      log.push(`device ${kind} ${id}`);
    },
    async setAudioOnly(on) {
      log.push(`audioOnly ${on}`);
    },
    attachLocal() {},
    attachRemote() {},
    async startAudio() {
      log.push('startAudio');
    },
    disconnect() {
      timers.forEach(clearTimeout);
      log.push('disconnect');
    },
  };
}
