const TOUCH_WHEEL_STEP_PX = 24;

/** Converts a physical drag into whole remote mouse-wheel ticks. */
export function touchWheelSteps(deltaPixels: number): number {
  return Math.trunc(deltaPixels / TOUCH_WHEEL_STEP_PX);
}

export function sgrWheelReport(button: 64 | 65, column: number, row: number): string {
  return `\u001b[<${button};${column};${row}M`;
}
