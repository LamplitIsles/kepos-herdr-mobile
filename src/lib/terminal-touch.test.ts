import { describe, expect, it } from "vitest";
import { sgrWheelReport, touchWheelSteps } from "./terminal-touch";

describe("terminal touch wheel", () => {
  it("does not turn a tap-sized movement into terminal scrolling", () => {
    expect(touchWheelSteps(23)).toBe(0);
  });

  it("turns a drag into whole remote wheel ticks", () => {
    expect(touchWheelSteps(-72)).toBe(-3);
  });

  it("uses the standard SGR wheel report", () => {
    expect(sgrWheelReport(64, 12, 8)).toBe("\u001b[<64;12;8M");
  });
});
