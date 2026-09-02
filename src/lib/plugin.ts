import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";

export interface DeviceKeyInfo {
  publicKey: string;
  algorithm: "RSA";
  bits: 3072;
}

export interface ConnectionTarget {
  host: string;
  port: number;
  user: string;
}

export interface DiscoveredTarget {
  serviceName: string;
  host: string;
  port: number;
}

export type ConnectResult =
  | { status: "connected"; sessionId: string; fingerprint: string }
  | { status: "trust-required"; fingerprint: string }
  | { status: "fingerprint-mismatch"; expectedFingerprint: string; observedFingerprint: string }
  | { status: "failed"; code: string; message: string };

export interface TrustResult {
  status: "accepted" | "replaced";
  fingerprint: string;
}

export interface TerminalFrame {
  sessionId: string;
  data: string;
}

export interface SessionStateEvent {
  sessionId: string;
  status: "disconnected";
  reason?: string;
}

export interface HerdrSshPlugin {
  getDeviceKey(): Promise<DeviceKeyInfo>;
  getTarget(): Promise<{ target?: ConnectionTarget }>;
  saveTarget(target: ConnectionTarget): Promise<{ target: ConnectionTarget }>;
  discoverTargets(): Promise<{ targets: DiscoveredTarget[] }>;
  connect(options: {
    columns: number;
    rows: number;
    pixelWidth?: number;
    pixelHeight?: number;
  }): Promise<ConnectResult>;
  confirmHostTrust(options: { fingerprint: string }): Promise<TrustResult>;
  replaceHostTrust(options: { fingerprint: string }): Promise<TrustResult>;
  sendInput(options: { sessionId: string; data: string }): Promise<void>;
  resize(options: {
    sessionId: string;
    columns: number;
    rows: number;
    pixelWidth?: number;
    pixelHeight?: number;
  }): Promise<void>;
  release(options: { sessionId: string }): Promise<void>;
  addListener(
    eventName: "terminalFrame",
    listenerFunc: (event: TerminalFrame) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "sessionState",
    listenerFunc: (event: SessionStateEvent) => void
  ): Promise<PluginListenerHandle>;
}

export const HerdrSsh = registerPlugin<HerdrSshPlugin>("HerdrSsh");
