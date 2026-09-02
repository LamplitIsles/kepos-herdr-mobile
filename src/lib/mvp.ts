import type {
  ConnectResult,
  ConnectionTarget,
  DeviceKeyInfo,
  DiscoveredTarget,
  HerdrSshPlugin,
  SessionStateEvent,
  TerminalFrame
} from "./plugin";

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
    try {
      const result = await this.plugin.connect({ columns: 80, rows: 24 });
      this.applyConnectResult(result);
      return result;
    } catch (error) {
      this.setState({ error: readableError(error, "Could not reach this host") });
      return null;
    }
  }

  private applyConnectResult(result: ConnectResult): void {
    if (result.status === "connected") {
      this.setState({ phase: "terminal", activeSessionId: result.sessionId, terminalText: "", error: null, trustPrompt: null, notice: "Connected · herdr is starting" });
    } else if (result.status === "trust-required") {
      this.setState({ phase: "ready", trustPrompt: { fingerprint: result.fingerprint, kind: "first-use" }, error: null });
    } else if (result.status === "fingerprint-mismatch") {
      this.setState({ phase: "ready", trustPrompt: { fingerprint: result.observedFingerprint, expectedFingerprint: result.expectedFingerprint, kind: "changed" }, error: "The server fingerprint changed. Verify the host before replacing its trust record." });
    } else {
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
    finally { this.setState({ phase: "ready", activeSessionId: null, notice: "Session disconnected" }); }
  }

  async dispose(): Promise<void> {
    await this.release();
    await this.frameHandle?.remove();
    await this.sessionHandle?.remove();
    this.frameHandle = null;
    this.sessionHandle = null;
  }

  private onTerminalFrame(frame: TerminalFrame): void {
    if (frame.sessionId !== this.current.activeSessionId) return;
    try { this.setState({ terminalText: this.current.terminalText + decodeBase64(frame.data) }); }
    catch { this.setState({ error: "Received unreadable terminal data" }); }
  }

  private onSessionState(event: SessionStateEvent): void {
    if (event.sessionId !== this.current.activeSessionId) return;
    this.setState({ phase: "ready", activeSessionId: null, notice: "Session disconnected", error: event.reason && event.reason !== "released" ? event.reason : null });
  }
}

export function readableError(error: unknown, fallback: string): string {
  if (typeof error === "object" && error !== null && "message" in error) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === "string" && message.trim()) return message;
  }
  return fallback;
}

function decodeBase64(value: string): string {
  const binary = globalThis.atob(value);
  return new TextDecoder().decode(Uint8Array.from(binary, (char) => char.charCodeAt(0)));
}
