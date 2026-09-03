<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { FitAddon } from "@xterm/addon-fit";
  import { Terminal } from "@xterm/xterm";
  import type { HerdrMvpController } from "./mvp";
  import { portraitTerminalDimensions } from "./terminal-size";
  import { sgrWheelReport, touchWheelSteps } from "./terminal-touch";

  export let controller: HerdrMvpController;
  export let sessionId: string;
  export let terminalText: string;

  let terminalElement: HTMLDivElement;
  let terminal: Terminal;
  let fitAddon: FitAddon;
  let renderedLength = 0;
  let resizeObserver: ResizeObserver | undefined;
  let removeTouchBridge: (() => void) | undefined;

  // Herdr exposes pointer-driven controls. Request standard SGR mouse reports
  // from xterm so an Android tap is forwarded instead of only focusing its
  // hidden textarea and opening the IME.
  const mouseReports = "\u001b[?1000h\u001b[?1006h";

  onMount(() => {
    terminal = new Terminal({
      cursorBlink: true,
      convertEol: false,
      scrollback: 2_000,
      fontFamily: "'JetBrains Mono', 'SFMono-Regular', Consolas, monospace",
      fontSize: 10,
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
    terminal.write(mouseReports);
    fitTerminal();
    terminal.onData((data) => void controller.sendInput(data));
    removeTouchBridge = installTouchScrollback();
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
    const proposed = fitAddon.proposeDimensions();
    if (!proposed) return;
    const dimensions = portraitTerminalDimensions(proposed);
    terminal.resize(dimensions.cols, dimensions.rows);
    void controller.resize(dimensions.cols, dimensions.rows);
  }

  function installTouchScrollback(): () => void {
    let lastY: number | undefined;
    let pendingPixels = 0;
    let scrolling = false;

    const start = (event: TouchEvent) => {
      lastY = event.touches[0]?.clientY;
      pendingPixels = 0;
      scrolling = false;
    };
    const move = (event: TouchEvent) => {
      const currentY = event.touches[0]?.clientY;
      if (lastY === undefined || currentY === undefined) return;
      pendingPixels += lastY - currentY;
      lastY = currentY;
      const steps = touchWheelSteps(pendingPixels);
      if (steps === 0) return;
      scrolling = true;
      event.preventDefault();
      const bounds = terminalElement.getBoundingClientRect();
      const column = Math.max(1, Math.min(terminal.cols, Math.floor((event.touches[0].clientX - bounds.left) / bounds.width * terminal.cols) + 1));
      const row = Math.max(1, Math.min(terminal.rows, Math.floor((event.touches[0].clientY - bounds.top) / bounds.height * terminal.rows) + 1));
      const button = steps > 0 ? 65 : 64;
      for (let index = 0; index < Math.abs(steps); index += 1) {
        void controller.sendInput(sgrWheelReport(button, column, row));
      }
      pendingPixels -= steps * 24;
    };
    const end = (event: TouchEvent) => {
      if (scrolling) event.preventDefault();
      lastY = undefined;
      pendingPixels = 0;
    };

    terminalElement.addEventListener("touchstart", start, { passive: true });
    terminalElement.addEventListener("touchmove", move, { passive: false });
    terminalElement.addEventListener("touchend", end, { passive: false });
    return () => {
      terminalElement.removeEventListener("touchstart", start);
      terminalElement.removeEventListener("touchmove", move);
      terminalElement.removeEventListener("touchend", end);
    };
  }

  onDestroy(() => {
    resizeObserver?.disconnect();
    removeTouchBridge?.();
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
