<script lang="ts">
  import { onMount } from "svelte";
  import Terminal from "./lib/Terminal.svelte";
  import { HerdrMvpController } from "./lib/mvp";
  import { HerdrSsh, type ConnectionTarget } from "./lib/plugin";

  type Tab = "hosts" | "activity" | "profile";
  const controller = new HerdrMvpController(HerdrSsh);
  let state = controller.state;
  let draft: ConnectionTarget = { host: "", port: 22, user: "" };
  let draftDirty = false;
  let copied = false;
  let sharing = false;
  let editingTarget = false;
  let activeTab: Tab = "hosts";

  const unsubscribe = controller.subscribe((next) => {
    state = next;
    if (!draftDirty && next.target) draft = { ...next.target };
    if (next.activeSessionId) activeTab = "activity";
  });

  onMount(() => {
    void controller.load();
    return () => { unsubscribe(); void controller.dispose(); };
  });

  async function submitTarget(): Promise<void> {
    const saved = await controller.saveTarget({ host: draft.host.trim(), port: Number(draft.port), user: draft.user.trim() });
    if (saved) { draftDirty = false; editingTarget = false; }
  }
  function chooseTarget(host: string, port: number): void { draft = { host, port, user: draft.user }; draftDirty = true; editingTarget = true; }
  async function copyPublicKey(): Promise<void> {
    if (!state.deviceKey) return;
    try { await navigator.clipboard?.writeText(state.deviceKey.publicKey); if (!navigator.clipboard) throw new Error("clipboard unavailable"); }
    catch { const area = document.createElement("textarea"); area.value = state.deviceKey.publicKey; area.setAttribute("readonly", "true"); area.style.position = "fixed"; area.style.opacity = "0"; document.body.appendChild(area); area.select(); document.execCommand("copy"); area.remove(); }
    copied = true; window.setTimeout(() => (copied = false), 1_800);
  }
  async function sharePublicKey(): Promise<void> {
    if (!state.deviceKey || !navigator.share) return copyPublicKey();
    sharing = true;
    try { await navigator.share({ title: "Herdr Device Key", text: state.deviceKey.publicKey }); }
    catch { /* Cancelling Android's share sheet is not an app error. */ }
    finally { sharing = false; }
  }
  function selectTab(tab: Tab): void {
    activeTab = tab;
    requestAnimationFrame(() => window.scrollTo(0, 0));
  }
</script>

<svelte:head><title>Herdr Mobile</title></svelte:head>
<main class:terminal-shell={Boolean(state.activeSessionId)} class="app-shell">
  <header class="topbar"><p class="wordmark">{state.activeSessionId ? "‹  Hosts" : "herdr"}</p><h1>{state.activeSessionId ? state.target?.host : activeTab === "hosts" ? "Launch" : activeTab === "activity" ? "Activity" : "Profile"}</h1>{#if state.activeSessionId}<button class="session-leave" type="button" on:click={() => controller.release()}>Leave</button>{:else}<span class="android-mark">Android</span>{/if}</header>
  {#if state.error}<div class="banner error-banner" role="alert">{state.error}</div>{:else if state.notice}<div class="banner notice-banner" role="status">{state.notice}</div>{/if}

  <div class:active={activeTab === "hosts"} class="tab-page hosts-page">
    <section class="section-heading"><h2>Hosts</h2><button class="icon-button" type="button" aria-label="Edit host" on:click={() => (editingTarget = !editingTarget)}>{editingTarget ? "−" : "+"}</button></section>
    {#if state.target}<button data-testid="host-connect" class="host-card" type="button" disabled={!state.deviceKey} on:click={() => controller.connect()}><span class="host-icon">▱</span><span class="host-copy"><strong>{state.target.host}</strong><small>{state.target.user || "user"} · port {state.target.port}</small></span><span class="trusted-pill">herdr</span></button>
    {:else}<button class="host-card empty-host" type="button" on:click={() => (editingTarget = true)}><span class="host-icon">+</span><span class="host-copy"><strong>Add your Mac</strong><small>LAN hostname or IP address</small></span><span class="chevron">›</span></button>{/if}
    {#if editingTarget}<section class="inset-panel" aria-label="Mac target settings"><div class="panel-title"><strong>Mac connection</strong><button class="text-button" type="button" on:click={() => controller.discover()} disabled={state.discoveryState === "searching"}>{state.discoveryState === "searching" ? "Scanning…" : "Discover"}</button></div>
      {#if state.discoveredTargets.length > 0}<div class="discovery-list">{#each state.discoveredTargets as found (found.serviceName + found.host + found.port)}<button class="discovery-item" type="button" on:click={() => chooseTarget(found.host, found.port)}><span>{found.serviceName}</span><small>{found.host}</small></button>{/each}</div>{:else if state.discoveryState === "searching"}<p class="muted">Searching for SSH services on this Wi-Fi network…</p>{/if}
      <form class="target-form" on:submit|preventDefault={submitTarget}><label>Mac host or IP<input bind:value={draft.host} on:input={() => (draftDirty = true)} placeholder="macbook.local" autocomplete="off" /></label><div class="form-split"><label>Port<input bind:value={draft.port} on:input={() => (draftDirty = true)} type="number" min="1" max="65535" inputmode="numeric" /></label><label>Mac user<input bind:value={draft.user} on:input={() => (draftDirty = true)} placeholder="neil" autocomplete="username" /></label></div><button class="primary-button" type="submit">Save host</button></form>
    </section>{/if}
    {#if state.trustPrompt}<section class="trust-card" aria-live="polite"><span class="trust-icon">!</span><div><strong>{state.trustPrompt.kind === "first-use" ? "Trust this Mac?" : "Host key changed"}</strong><p>Verify the fingerprint before saving it.</p><code>{state.trustPrompt.fingerprint}</code><div class="action-row"><button class="primary-button" type="button" on:click={() => controller.confirmTrust(state.trustPrompt?.kind === "changed")}>{state.trustPrompt.kind === "first-use" ? "Trust & connect" : "Replace & connect"}</button><button class="plain-button" type="button" on:click={() => controller.dismissTrustPrompt()}>Cancel</button></div></div></section>
    {/if}
  </div>

  <div class:active={activeTab === "activity"} class="tab-page activity-page">
    {#if state.activeSessionId}<section class="terminal-card"><Terminal controller={controller} sessionId={state.activeSessionId} terminalText={state.terminalText} /></section>
    {:else}<section class="empty-state"><span>›_</span><strong>No active terminal</strong><p>Open Herdr from the Hosts tab to see this session here.</p><button class="primary-button" type="button" on:click={() => selectTab("hosts")}>Go to Hosts</button></section>{/if}
  </div>

  <div class:active={activeTab === "profile"} class="tab-page profile-page">
    <section class="account-card"><span class="account-mark">H</span><div><strong>Herdr Mobile</strong><small>Android · device-bound SSH</small></div><span class="free-pill">LOCAL</span></section>
    <section class="settings-card"><div class="setting-row"><span class="setting-icon">⌘</span><div><strong>Device Key</strong><small>One RSA-3072 key for every backend</small></div><span class="trusted-pill">ready</span></div>{#if state.deviceKey}<div class="key-preview">{state.deviceKey.publicKey}</div><div class="action-row"><button class="primary-button" type="button" on:click={copyPublicKey}>{copied ? "Copied" : "Copy public key"}</button><button class="plain-button" type="button" on:click={sharePublicKey} disabled={sharing}>{sharing ? "Sharing…" : "Share"}</button></div><p class="key-hint">Add the public key to <code>~/.ssh/authorized_keys</code> on each Mac.</p>{:else}<p class="muted">Creating the non-exportable Android Keystore key…</p>{/if}</section>
    <section class="settings-card compact-settings"><button class="setting-row button-row-link" type="button" on:click={() => selectTab("hosts")}><span class="setting-icon">▱</span><span><strong>Manage Hosts</strong><small>Discovery and saved target</small></span><span class="chevron">›</span></button><div class="setting-row"><span class="setting-icon">✓</span><div><strong>Host Trust</strong><small>Strict TOFU verification</small></div></div></section>
  </div>
  <nav class="bottom-nav" aria-label="Main navigation"><button data-testid="nav-hosts" class:current={activeTab === "hosts"} type="button" on:click={() => selectTab("hosts")}><span>▱</span>Hosts</button><button data-testid="nav-activity" class:current={activeTab === "activity"} type="button" on:click={() => selectTab("activity")}><span>›_</span>Activity</button><button data-testid="nav-profile" class:current={activeTab === "profile"} type="button" on:click={() => selectTab("profile")}><span>●</span>Profile</button></nav>
</main>
