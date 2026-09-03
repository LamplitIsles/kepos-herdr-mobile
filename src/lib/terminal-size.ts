// Largest verified phone-layout width on the supported Android viewport.
// Real cells are never stretched to simulate a wider terminal.
export const PORTRAIT_MAX_COLUMNS = 64;
export const PORTRAIT_INITIAL_ROWS = 24;

export interface TerminalDimensions {
  cols: number;
  rows: number;
}

/**
 * Herdr selects its phone layout from the PTY column count. Keep the remote
 * viewport narrow even when an Android WebView offers more horizontal pixels.
 */
export function portraitTerminalDimensions(dimensions: TerminalDimensions): TerminalDimensions {
  return {
    cols: Math.min(dimensions.cols, PORTRAIT_MAX_COLUMNS),
    rows: dimensions.rows
  };
}
