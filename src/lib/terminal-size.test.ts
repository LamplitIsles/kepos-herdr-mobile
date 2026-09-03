import { describe, expect, it } from "vitest";
import { PORTRAIT_MAX_COLUMNS, portraitTerminalDimensions } from "./terminal-size";

describe("portraitTerminalDimensions", () => {
  it("limits a wide xterm viewport to the Herdr phone layout", () => {
    expect(portraitTerminalDimensions({ cols: 80, rows: 24 })).toEqual({
      cols: PORTRAIT_MAX_COLUMNS,
      rows: 24
    });
  });

  it("keeps an already narrow viewport unchanged", () => {
    expect(portraitTerminalDimensions({ cols: 36, rows: 42 })).toEqual({ cols: 36, rows: 42 });
  });

});
