<script lang="ts">
  import { onMount } from "svelte";
  import Terminal from "./lib/Terminal.svelte";
  import { HerdrMvpController } from "./lib/mvp";
  import { HerdrSsh, type ConnectionTarget } from "./lib/plugin";

  const controller = new HerdrMvpController(HerdrSsh);
  let state = controller.state;
  let draft: ConnectionTarget = { host: "", port: 22, user: "" };
  let draftDirty = false;
  let copied = false;
  let sharing = false;

  const unsubscribe = controller.subscribe((next) => {
    state = next;
    if (!draftDirty && next.target) draft = { ...next.target };
  });

  onMount(() => {
    void controller.load();
    return () => {
      unsubscribe();
      void controller.dispose();
    };
  });

  async function submitTarget(): Promise<void> {
    const saved = await controller.saveTarget({
      host: draft.host.trim(),
      port: Number(draft.port),
      user: draft.user.trim()
    });
    if (saved) draftDirty = false;
  }

  function chooseTarget(host: string, port: number): void {
    draft = { host, port, user: draft.user };
    draftDirty = true;
  }

  async function copyPublicKey(): Promise<void> {
    if (!state.deviceKey) return;
    try {
      await navigator.clipboard?.writeText(state.deviceKey.publicKey);
      if (!navigator.clipboard) throw new Error("clipboard unavailable");
    } catch {
      const area = document.createElement("textarea");
      area.value = state.deviceKey.publicKey;
      area.setAttribute("readonly", "true");
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      document.execCommand("copy");
      area.remove();
    }
    copied = true;
    window.setTimeout(() => (copied = false), 1_800);
  }

  async function sharePublicKey(): Promise<void> {
    if (!state.deviceKey || !navigator.share) return copyPublicKey();
    sharing = true;
    try { await navigator.share({ title: "Herdr Device Key", text: state.deviceKey.publicKey }); }
    catch { /* cancelling Android's share sheet is not an app error */ }
    finally { sharing = false; }
  }
</script>

<svelte:head><title>Herdr Mobile · one direct target</title></svelte:head>

<main class="app-shell">
  <header class="topbar">
    <div class="brand-lockup"><div class="brand-mark">H</div><div><p class="eyebrow">KEPOS / HERDR</p><h1>Mobile console</h1></div></div>
    <div class="security-pill"><span class="shield">◆</span> Android · device-bound SSH</div>
  </header>

  {#if state.error}
    <div class="banner error-banner" role="alert"><span class="banner-icon">!</span><span>{state.error}</span></div>
  {:else if state.notice}
    <div class="banner notice-banner" role="status"><span class="banner-icon">✓</span><span>{state.notice}</span></div>
  {/if}

  <section class="route-grid">
    <section class="card intro-card">
      <div>
        <p class="eyebrow">DIRECT SSH / ONE MAC TARGET</p>
        <h2>Pick up where your work lives.</h2>
        <p class="hero-copy">Discover the Mac on your LAN, verify its key, and keep one focused Herdr terminal in the foreground.</p>
      </div>
      <div class="hero-orbit"><span></span><span></span><span></span><b>↗</b></div>
    </section>

    <section class="route-columns">
      <div class="left-stack">
        <section class="card key-card">
          <div class="card-heading"><div><p class="eyebrow">01 · identity</p><h2>Device Key</h2></div><span class="key-badge">RSA · 3072</span></div>
          {#if state.deviceKey}
            <p class="muted">Generated once in Android Keystore. Only this public text can leave the app.</p>
            <div class="key-preview">{state.deviceKey.publicKey}</div>
            <div class="button-row"><button class="button primary" type="button" on:click={copyPublicKey}>{copied ? "Copied" : "Copy key"}</button><button class="button quiet" type="button" on:click={sharePublicKey} disabled={sharing}>{sharing ? "Sharing…" : "Share"}</button></div>
            <p class="hint">Add this line manually to the Mac account’s <code>authorized_keys</code>.</p>
          {:else if state.phase === "loading"}
            <p class="muted">Creating a non-exportable key inside this device…</p><div class="skeleton-line"></div>
          {:else}
            <p class="muted">The Device Key could not be loaded. Check Android Keystore availability.</p>
          {/if}
        </section>

        <section class="card discover-card">
          <div class="card-heading"><div><p class="eyebrow">02 · find the Mac</p><h2>Connection Target</h2></div><button class="text-button" type="button" on:click={() => controller.discover()} disabled={state.discoveryState === "searching"}>{state.discoveryState === "searching" ? "scanning…" : "scan again"}</button></div>
          {#if state.discoveryState === "searching"}
            <p class="muted">Looking for <code>_ssh._tcp</code> services on this LAN…</p>
          {:else if state.discoveredTargets.length > 0}
            <p class="muted">Found SSH services nearby. Choose one, then add the Mac user.</p>
            <div class="discovery-list">
              {#each state.discoveredTargets as found (found.serviceName + found.host + found.port)}
                <button class="discovery-item" type="button" on:click={() => chooseTarget(found.host, found.port)}><span class="service-orb">⌁</span><span><strong>{found.serviceName}</strong><small>{found.host}:{found.port}</small></span><span class="arrow">→</span></button>
              {/each}
            </div>
          {:else}
            <p class="muted">No SSH service was found automatically. Enter the Mac hostname or LAN IP below.</p>
          {/if}
          <form class="target-form" on:submit|preventDefault={submitTarget}>
            <label>Mac host or IP<input bind:value={draft.host} on:input={() => (draftDirty = true)} placeholder="macbook.local or 192.168.1.42" autocomplete="off" /></label>
            <div class="form-split"><label>SSH port<input bind:value={draft.port} on:input={() => (draftDirty = true)} type="number" min="1" max="65535" inputmode="numeric" /></label><label>Mac user<input bind:value={draft.user} on:input={() => (draftDirty = true)} placeholder="neil" autocomplete="username" /></label></div>
            <button class="button secondary full-width" type="submit">{state.target ? "Save target" : "Save Mac target"}</button>
          </form>
          {#if state.target}<p class="saved-target"><span class="online-dot"></span> saved · {state.target.user || "user"}@{state.target.host}:{state.target.port}</p>{/if}
        </section>
      </div>

      <div class="right-stack">
        {#if state.trustPrompt}
          <section class="card trust-card" aria-live="polite">
            <div class="trust-icon">⌁</div><div class="trust-content"><p class="eyebrow">03 · verify before connecting</p><h2>{state.trustPrompt.kind === "first-use" ? "Trust this Mac?" : "Host key changed"}</h2>
              {#if state.trustPrompt.kind === "first-use"}<p class="muted">Compare this fingerprint with an independent check on the Mac before saving its Host Trust Record.</p>{:else}<p class="muted">The saved Host Trust Record no longer matches. Replace it only if the Mac was intentionally re-provisioned.</p><div class="fingerprint old">saved · {state.trustPrompt.expectedFingerprint}</div>{/if}
              <div class="fingerprint">observed · {state.trustPrompt.fingerprint}</div><div class="button-row"><button class="button primary" type="button" on:click={() => controller.confirmTrust(state.trustPrompt?.kind === "changed")}>{state.trustPrompt.kind === "first-use" ? "Trust & connect" : "Replace & connect"}</button><button class="button quiet" type="button" on:click={() => controller.dismissTrustPrompt()}>Cancel</button></div>
            </div>
          </section>
        {:else if state.activeSessionId}
          <section class="card terminal-card"><div class="terminal-heading"><div><p class="eyebrow">04 · remote session</p><h2>herdr on {state.target?.host}</h2></div><button class="button danger" type="button" on:click={() => controller.release()}>Leave terminal</button></div><Terminal controller={controller} sessionId={state.activeSessionId} terminalText={state.terminalText} /><p class="terminal-help">Touch, scroll, and type inside the terminal. Backgrounding the app disconnects this session.</p></section>
        {:else}
          <section class="card connect-card"><div class="connect-icon">⌘</div><div class="connect-copy"><p class="eyebrow">03 · open a session</p><h2>{state.target ? `Connect to ${state.target.host}` : "Save the Mac target to connect"}</h2><p class="muted">The app will show the server fingerprint before any trust record is saved.</p></div><button class="button primary connect-button" type="button" disabled={!state.target || !state.deviceKey} on:click={() => controller.connect()}>Open Herdr <span>↗</span></button></section>
        {/if}
      </div>
    </section>
  </section>

  <footer class="footer"><span>Herdr Mobile · Android only</span><span>Keystore key <span class="footer-dot">●</span> direct TCP <span class="footer-dot">●</span> no background sessions</span></footer>
</main>
