import { describe, expect, it } from "vitest";
import { HerdrMvpController } from "./mvp";
import { PORTRAIT_INITIAL_ROWS, PORTRAIT_MAX_COLUMNS } from "./terminal-size";
import type {
  ConnectResult,
  ConnectionTarget,
  DeviceKeyInfo,
  DiscoveredTarget,
  HerdrSshPlugin,
  SessionStateEvent,
  TerminalFrame
} from "./plugin";

const deviceKey: DeviceKeyInfo = { publicKey: "ssh-rsa AAAA test", algorithm: "RSA", bits: 3072 };
const target: ConnectionTarget = { host: "macbook.local", port: 22, user: "neil" };

class FakePlugin implements HerdrSshPlugin {
  savedTarget: ConnectionTarget | undefined = target;
  discoveries: DiscoveredTarget[] = [{ serviceName: "Neil's Mac", host: "192.168.1.42", port: 22 }];
  connectResults: ConnectResult[] = [];
  confirmed: string[] = [];
  replaced: string[] = [];
  sent: string[] = [];
  resized: Array<{ columns: number; rows: number }> = [];
  released: string[] = [];
  activated: string[] = [];
  connectOptions: Array<{ columns: number; rows: number }> = [];
  beforeConnectResult: (() => void) | undefined;
  private frameListeners = new Set<(event: TerminalFrame) => void>();
  private stateListeners = new Set<(event: SessionStateEvent) => void>();

  getDeviceKey(): Promise<DeviceKeyInfo> { return Promise.resolve(deviceKey); }
  getTarget(): Promise<{ target?: ConnectionTarget }> { return Promise.resolve({ target: this.savedTarget }); }
  saveTarget(next: ConnectionTarget): Promise<{ target: ConnectionTarget }> { this.savedTarget = next; return Promise.resolve({ target: next }); }
  discoverTargets(): Promise<{ targets: DiscoveredTarget[] }> { return Promise.resolve({ targets: this.discoveries }); }
  connect(options: { columns: number; rows: number }): Promise<ConnectResult> {
    this.connectOptions.push(options);
    this.beforeConnectResult?.();
    return Promise.resolve(this.connectResults.shift() ?? { status: "failed", code: "test", message: "test failure" });
  }
  confirmHostTrust(value: { fingerprint: string }): Promise<{ status: "accepted"; fingerprint: string }> { this.confirmed.push(value.fingerprint); return Promise.resolve({ status: "accepted", ...value }); }
  replaceHostTrust(value: { fingerprint: string }): Promise<{ status: "replaced"; fingerprint: string }> { this.replaced.push(value.fingerprint); return Promise.resolve({ status: "replaced", ...value }); }
  activate({ sessionId }: { sessionId: string }): Promise<void> { this.activated.push(sessionId); return Promise.resolve(); }
  sendInput({ data }: { sessionId: string; data: string }): Promise<void> { this.sent.push(data); return Promise.resolve(); }
  resize(value: { sessionId: string; columns: number; rows: number }): Promise<void> { this.resized.push(value); return Promise.resolve(); }
  release({ sessionId }: { sessionId: string }): Promise<void> { this.released.push(sessionId); return Promise.resolve(); }
  addListener(eventName: "terminalFrame" | "sessionState", listener: (event: any) => void): Promise<{ remove: () => Promise<void> }> {
    const listeners = eventName === "terminalFrame" ? this.frameListeners : this.stateListeners;
    listeners.add(listener);
    return Promise.resolve({ remove: async () => { listeners.delete(listener); } });
  }
  emitFrame(data: string, sessionId = "session-1"): void { for (const listener of this.frameListeners) listener({ sessionId, data }); }
  emitDisconnected(sessionId = "session-1"): void { for (const listener of this.stateListeners) listener({ sessionId, status: "disconnected", reason: "Mac closed the session" }); }
}

describe("HerdrMvpController one-target route", () => {
  it("loads the Device Key, saved target, and best-effort discovery", async () => {
    const plugin = new FakePlugin();
    const controller = new HerdrMvpController(plugin);

    await controller.load();

    expect(controller.state.deviceKey).toEqual(deviceKey);
    expect(controller.state.target).toEqual(target);
    expect(controller.state.discoveredTargets).toEqual(plugin.discoveries);
    expect(controller.state.discoveryState).toBe("complete");
  });

  it("keeps the manual route usable when LAN discovery is unavailable", async () => {
    const plugin = new FakePlugin();
    plugin.discoverTargets = () => Promise.reject(new Error("NSD unavailable"));
    const controller = new HerdrMvpController(plugin);

    await controller.load();

    expect(controller.state.discoveryState).toBe("unavailable");
    expect(controller.state.target).toEqual(target);
    expect(controller.state.error).toBe("NSD unavailable");
  });

  it("saves a manually entered target when discovery has no result", async () => {
    const plugin = new FakePlugin();
    plugin.savedTarget = undefined;
    plugin.discoveries = [];
    const controller = new HerdrMvpController(plugin);

    await controller.load();
    expect(controller.state.discoveredTargets).toEqual([]);
    expect(controller.state.target).toBeNull();

    expect(await controller.saveTarget({ host: "192.168.1.42", port: 22, user: "neil" })).toBe(true);
    expect(controller.state.target).toEqual({ host: "192.168.1.42", port: 22, user: "neil" });
  });

  it("shows a first-use fingerprint and connects only after explicit trust", async () => {
    const plugin = new FakePlugin();
    plugin.connectResults = [
      { status: "trust-required", fingerprint: "SHA256:first" },
      { status: "connected", sessionId: "session-1", fingerprint: "SHA256:first" }
    ];
    const controller = new HerdrMvpController(plugin);
    await controller.load();

    await controller.connect();
    expect(controller.state.trustPrompt).toEqual({ kind: "first-use", fingerprint: "SHA256:first" });
    expect(plugin.connectOptions).toEqual([{ columns: PORTRAIT_MAX_COLUMNS, rows: PORTRAIT_INITIAL_ROWS }]);
    expect(controller.state.activeSessionId).toBeNull();

    await controller.confirmTrust();
    expect(plugin.confirmed).toEqual(["SHA256:first"]);
    expect(controller.state.activeSessionId).toBe("session-1");
    expect(controller.state.phase).toBe("terminal");
    expect(controller.state.notice).toBeNull();
  });

  it("surfaces a changed fingerprint and uses a separate replacement action", async () => {
    const plugin = new FakePlugin();
    plugin.connectResults = [
      { status: "fingerprint-mismatch", expectedFingerprint: "SHA256:old", observedFingerprint: "SHA256:new" },
      { status: "connected", sessionId: "session-1", fingerprint: "SHA256:new" }
    ];
    const controller = new HerdrMvpController(plugin);
    await controller.load();

    await controller.connect();
    expect(controller.state.trustPrompt).toMatchObject({ kind: "changed", expectedFingerprint: "SHA256:old" });
    await controller.confirmTrust(true);
    expect(plugin.replaced).toEqual(["SHA256:new"]);
    expect(controller.state.activeSessionId).toBe("session-1");
  });

  it("forwards terminal input and resize, then releases on disconnect", async () => {
    const plugin = new FakePlugin();
    plugin.connectResults = [{ status: "connected", sessionId: "session-1", fingerprint: "SHA256:first" }];
    const controller = new HerdrMvpController(plugin);
    await controller.load();
    await controller.connect();

    await controller.sendInput("ls\n");
    expect(plugin.activated).toEqual(["session-1"]);
    await controller.resize(100, 30);
    plugin.emitFrame("aGVyZHI=", "session-1");
    expect(plugin.sent).toEqual(["ls\n"]);
    expect(plugin.resized).toEqual([{ sessionId: "session-1", columns: 100, rows: 30 }]);
    expect(controller.state.terminalText).toBe("herdr");

    plugin.emitDisconnected();
    expect(controller.state.activeSessionId).toBeNull();
    expect(controller.state.phase).toBe("ready");
    expect(controller.state.error).toBe("Mac closed the session");
  });

  it("replays terminal frames received before connect resolves", async () => {
    const plugin = new FakePlugin();
    plugin.connectResults = [{ status: "connected", sessionId: "session-early", fingerprint: "SHA256:first" }];
    const controller = new HerdrMvpController(plugin);
    await controller.load();
    plugin.beforeConnectResult = () => plugin.emitFrame("aGVyZHI=", "session-early");

    await controller.connect();

    expect(controller.state.activeSessionId).toBe("session-early");
    expect(controller.state.terminalText).toBe("herdr");
  });

  it("preserves a UTF-8 character split across terminal frames", async () => {
    const plugin = new FakePlugin();
    plugin.connectResults = [{ status: "connected", sessionId: "session-1", fingerprint: "SHA256:first" }];
    const controller = new HerdrMvpController(plugin);
    await controller.load();
    await controller.connect();

    const bytes = new TextEncoder().encode("你");
    plugin.emitFrame(base64(bytes.slice(0, 2)));
    plugin.emitFrame(base64(bytes.slice(2)));

    expect(controller.state.terminalText).toBe("你");
  });

  it("releases an active session and ignores stale frames", async () => {
    const plugin = new FakePlugin();
    plugin.connectResults = [{ status: "connected", sessionId: "session-1", fingerprint: "SHA256:first" }];
    const controller = new HerdrMvpController(plugin);
    await controller.load();
    await controller.connect();

    await controller.release();
    expect(plugin.released).toEqual(["session-1"]);
    expect(controller.state.activeSessionId).toBeNull();
    plugin.emitFrame("c3RhbGU=", "session-1");
    expect(controller.state.terminalText).toBe("");
  });
});

function base64(bytes: Uint8Array): string {
  return globalThis.btoa(String.fromCharCode(...bytes));
}
