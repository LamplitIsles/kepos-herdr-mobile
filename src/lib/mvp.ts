import type {
  ConnectResult,
  ConnectionTarget,
  DeviceKeyInfo,
  DiscoveredTarget,
  HerdrSshPlugin,
  SessionStateEvent,
  TerminalFrame
} from "./plugin";
import { PORTRAIT_INITIAL_ROWS, PORTRAIT_MAX_COLUMNS } from "./terminal-size";

export type UiPhase = "loading" | "setup" | "ready" | "terminal";
export type DiscoveryState = "idle" | "searching" | "complete" | "unavailable";

export interface TrustPrompt {
  fingerprint: string;
  kind: "first-use" | "changed";
  expectedFingerprint?: string;
}

export interface MvpState {
  phase: UiPhase;
  deviceKey: DeviceKeyInfo | null;
  target: ConnectionTarget | null;
  discoveredTargets: DiscoveredTarget[];
  discoveryState: DiscoveryState;
  activeSessionId: string | null;
  terminalText: string;
  trustPrompt: TrustPrompt | null;
  error: string | null;
  notice: string | null;
}

export function initialState(): MvpState {
  return {
    phase: "loading",
    deviceKey: null,
    target: null,
    discoveredTargets: [],
    discoveryState: "idle",
    activeSessionId: null,
    terminalText: "",
    trustPrompt: null,
    error: null,
    notice: null
  };
}

export type StateListener = (state: MvpState) => void;

/** UI state seam for the one-target vertical route. Native owns key and SSH decisions. */
export class HerdrMvpController {
  private current: MvpState = initialState();
  private listeners = new Set<StateListener>();
  private frameHandle: { remove: () => Promise<void> } | null = null;
  private sessionHandle: { remove: () => Promise<void> } | null = null;
  private connecting = false;
  private pendingFrames = new Map<string, string>();
  private frameDecoders = new Map<string, TextDecoder>();

  constructor(private readonly plugin: HerdrSshPlugin) {}

  get state(): MvpState {
    return this.current;
  }

  subscribe(listener: StateListener): () => void {
    this.listeners.add(listener);
    listener(this.current);
    return () => this.listeners.delete(listener);
  }

  private setState(patch: Partial<MvpState>): void {
    this.current = { ...this.current, ...patch };
    for (const listener of this.listeners) listener(this.current);
  }

  async load(): Promise<void> {
    let deviceKey: DeviceKeyInfo | null = null;
    let target: ConnectionTarget | null = null;
    let loadError: string | null = null;
    try {
      deviceKey = await this.plugin.getDeviceKey();
    } catch (error) {
      loadError = readableError(error, "Unable to initialize the Device Key");
    }
    try {
      target = (await this.plugin.getTarget()).target ?? null;
    } catch (error) {
      loadError ??= readableError(error, "Unable to load the Connection Target");
    }
    this.setState({
      phase: deviceKey ? "ready" : "setup",
      deviceKey,
      target,
      error: loadError
    });
    this.frameHandle = await this.plugin.addListener("terminalFrame", (frame) => this.onTerminalFrame(frame));
    this.sessionHandle = await this.plugin.addListener("sessionState", (event) => this.onSessionState(event));
    await this.discover(false);
  }

  async discover(clearError = true): Promise<void> {
    this.setState({ discoveryState: "searching", error: clearError ? null : this.current.error });
    try {
      const result = await this.plugin.discoverTargets();
      this.setState({ discoveredTargets: result.targets, discoveryState: "complete" });
    } catch (error) {
      this.setState({ discoveredTargets: [], discoveryState: "unavailable", error: readableError(error, "LAN discovery is unavailable; enter the host manually") });
    }
  }

  chooseDiscoveredTarget(target: DiscoveredTarget): void {
    this.setState({
      target: { host: target.host, port: target.port, user: this.current.target?.user ?? "" },
      error: null,
      notice: `Found ${target.serviceName}`
    });
  }

  async saveTarget(target: ConnectionTarget): Promise<boolean> {
    this.setState({ error: null, notice: null });
    try {
      const result = await this.plugin.saveTarget(target);
      this.setState({ target: result.target, phase: "ready", notice: "Connection Target saved" });
      return true;
    } catch (error) {
      this.setState({ error: readableError(error, "Check the host, port, and user") });
      return false;
    }
  }

  async connect(): Promise<ConnectResult | null> {
    if (!this.current.target) {
      this.setState({ error: "Enter and save the Connection Target first" });
      return null;
    }
    this.setState({ error: null, notice: null, trustPrompt: null });
    this.connecting = true;
    try {
      // Herdr decides between desktop and phone layouts from the initial PTY
      // dimensions, before xterm has mounted and can report its fitted size.
      const result = await this.plugin.connect({
        columns: PORTRAIT_MAX_COLUMNS,
        rows: PORTRAIT_INITIAL_ROWS
      });
      this.applyConnectResult(result);
      if (result.status === "connected") {
        // Let Svelte mount xterm before Herdr paints its full-screen first frame.
        await new Promise<void>((resolve) => globalThis.setTimeout(resolve, 0));
        await this.plugin.activate({ sessionId: result.sessionId });
      }
      return result;
    } catch (error) {
      this.pendingFrames.clear();
      this.frameDecoders.clear();
      this.setState({ error: readableError(error, "Could not reach this host") });
      return null;
    } finally {
      this.connecting = false;
    }
  }

  private applyConnectResult(result: ConnectResult): void {
    if (result.status === "connected") {
      const initialFrame = this.pendingFrames.get(result.sessionId) ?? "";
      this.pendingFrames.clear();
      this.setState({ phase: "terminal", activeSessionId: result.sessionId, terminalText: initialFrame, error: null, trustPrompt: null, notice: null });
    } else if (result.status === "trust-required") {
      this.pendingFrames.clear();
      this.frameDecoders.clear();
      this.setState({ phase: "ready", trustPrompt: { fingerprint: result.fingerprint, kind: "first-use" }, error: null });
    } else if (result.status === "fingerprint-mismatch") {
      this.pendingFrames.clear();
      this.frameDecoders.clear();
      this.setState({ phase: "ready", trustPrompt: { fingerprint: result.observedFingerprint, expectedFingerprint: result.expectedFingerprint, kind: "changed" }, error: "The server fingerprint changed. Verify the host before replacing its trust record." });
    } else {
      this.pendingFrames.clear();
      this.frameDecoders.clear();
      this.setState({ phase: "ready", error: result.message, trustPrompt: null });
    }
  }

  async confirmTrust(replace = false): Promise<boolean> {
    const prompt = this.current.trustPrompt;
    if (!prompt) return false;
    try {
      if (replace) await this.plugin.replaceHostTrust({ fingerprint: prompt.fingerprint });
      else await this.plugin.confirmHostTrust({ fingerprint: prompt.fingerprint });
      this.setState({ trustPrompt: null, error: null, notice: replace ? "Host Trust Record replaced" : "Host fingerprint trusted" });
      await this.connect();
      return true;
    } catch (error) {
      this.setState({ error: readableError(error, "The fingerprint was not saved") });
      return false;
    }
  }

  dismissTrustPrompt(): void {
    this.setState({ trustPrompt: null, error: null, notice: "Connection paused" });
  }

  async sendInput(data: string): Promise<void> {
    const sessionId = this.current.activeSessionId;
    if (!sessionId) return;
    try { await this.plugin.sendInput({ sessionId, data }); }
    catch (error) { this.setState({ error: readableError(error, "The terminal is disconnected") }); }
  }

  async resize(columns: number, rows: number): Promise<void> {
    const sessionId = this.current.activeSessionId;
    if (!sessionId) return;
    try { await this.plugin.resize({ sessionId, columns, rows }); }
    catch (error) { this.setState({ error: readableError(error, "The terminal could not be resized") }); }
  }

  async release(): Promise<void> {
    const sessionId = this.current.activeSessionId;
    if (!sessionId) return;
    try { await this.plugin.release({ sessionId }); }
    finally {
      this.appendTerminalText(this.flushFrameDecoder(sessionId));
      this.setState({ phase: "ready", activeSessionId: null, notice: "Session disconnected" });
    }
  }

  async dispose(): Promise<void> {
    await this.release();
    await this.frameHandle?.remove();
    await this.sessionHandle?.remove();
    this.frameHandle = null;
    this.sessionHandle = null;
  }

  private onTerminalFrame(frame: TerminalFrame): void {
    const isActiveSession = frame.sessionId === this.current.activeSessionId;
    const isPendingSession = this.connecting && this.current.activeSessionId === null;
    if (!isActiveSession && !isPendingSession) return;

    let decoded: string;
    try { decoded = this.decodeTerminalFrame(frame); }
    catch {
      this.setState({ error: "Received unreadable terminal data" });
      return;
    }
    if (isActiveSession) {
      this.appendTerminalText(decoded);
      return;
    }
    if (isPendingSession) {
      this.pendingFrames.set(frame.sessionId, (this.pendingFrames.get(frame.sessionId) ?? "") + decoded);
    }
  }

  private onSessionState(event: SessionStateEvent): void {
    if (event.sessionId !== this.current.activeSessionId) return;
    this.appendTerminalText(this.flushFrameDecoder(event.sessionId));
    this.setState({ phase: "ready", activeSessionId: null, notice: "Session disconnected", error: event.reason && event.reason !== "released" ? event.reason : null });
  }

  private decodeTerminalFrame(frame: TerminalFrame): string {
    const decoder = this.frameDecoders.get(frame.sessionId) ?? new TextDecoder();
    this.frameDecoders.set(frame.sessionId, decoder);
    return decoder.decode(decodeBase64(frame.data), { stream: true });
  }

  private flushFrameDecoder(sessionId: string): string {
    const decoder = this.frameDecoders.get(sessionId);
    this.frameDecoders.delete(sessionId);
    return decoder?.decode() ?? "";
  }

  private appendTerminalText(text: string): void {
    if (text) this.setState({ terminalText: this.current.terminalText + text });
  }
}

export function readableError(error: unknown, fallback: string): string {
  if (typeof error === "object" && error !== null && "message" in error) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === "string" && message.trim()) return message;
  }
  return fallback;
}

function decodeBase64(value: string): Uint8Array {
  const binary = globalThis.atob(value);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}
