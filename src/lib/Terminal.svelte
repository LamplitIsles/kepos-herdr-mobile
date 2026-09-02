<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { FitAddon } from "@xterm/addon-fit";
  import { Terminal } from "@xterm/xterm";
  import type { HerdrMvpController } from "./mvp";

  export let controller: HerdrMvpController;
  export let sessionId: string;
  export let terminalText: string;

  let terminalElement: HTMLDivElement;
  let terminal: Terminal;
  let fitAddon: FitAddon;
  let renderedLength = 0;
  let resizeObserver: ResizeObserver | undefined;

  onMount(() => {
    terminal = new Terminal({
      cursorBlink: true,
      convertEol: false,
      scrollback: 2_000,
      fontFamily: "'JetBrains Mono', 'SFMono-Regular', Consolas, monospace",
      fontSize: 13,
      theme: {
        background: "#10231e",
        foreground: "#e7f1e6",
        cursor: "#f4c95d",
        selectionBackground: "#316254"
      }
    });
    fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.open(terminalElement);
    fitTerminal();
    terminal.onData((data) => void controller.sendInput(data));
    resizeObserver = new ResizeObserver(() => fitTerminal());
    resizeObserver.observe(terminalElement);
  });

  $: if (terminal && terminalText.length < renderedLength) {
    terminal.reset();
    renderedLength = 0;
  }

  $: if (terminal && terminalText.length > renderedLength) {
    terminal.write(terminalText.slice(renderedLength));
    renderedLength = terminalText.length;
  }

  function fitTerminal(): void {
    if (!terminal || !fitAddon) return;
    fitAddon.fit();
    void controller.resize(terminal.cols, terminal.rows);
  }

  onDestroy(() => {
    resizeObserver?.disconnect();
    terminal?.dispose();
  });
</script>

<div class="terminal-frame" aria-label="Herdr terminal">
  <div class="terminal-toolbar">
    <span class="terminal-dot"></span>
    <span class="terminal-label">foreground session</span>
    <span class="terminal-session">{sessionId.slice(0, 8)}</span>
  </div>
  <div bind:this={terminalElement} class="terminal-canvas"></div>
</div>
