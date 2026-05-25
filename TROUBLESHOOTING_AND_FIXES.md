# Troubleshooting Ledger and Architectural Fixes

## Problem 1: Touch Event Propagation (Gesture Bubbling Deadlocks)
- **The Problem**: A recurring issue emerged where users scrolling or dragging inside a floating menu or the Wave Plotter graph caused touch inputs to bleed through to the underlying circuit canvas. This resulted in accidental component dragging or unintended workspace panning.
- **The Fix**: We implemented strict pointer input filtering and event consumption hooks. By utilizing `PointerInputChange.consume()` within the interaction bounds of active panels, event propagation is immediately halted. This ensures interactions remain isolated to the focused layout element.

## Problem 2: Viewport Clipping & Inaccessible UI Triggers
- **The Problem**: On small-screen mobile devices, dragging windows or the Waveform button near the edges often pushed controls completely off-screen. This rendered them unclickable and permanently lost to the user.
- **The Fix**: We introduced anti-clipping edge collision guardrails to the layout engine. By enforcing universal container `overflow: auto` attributes and mathematically clamping the maximum drag coordinates to the viewport's physical dimensions, UI elements are prevented from exiting the screen bounds. If any part of a window layout threatens to overflow, the system automatically generates multi-axis, 1-finger viewport scrolling boundaries.

## Problem 3: Multi-Touch Collision inside Graph Viewports
- **The Problem**: Attempting to pinch-to-zoom on a waveform graph inadvertently triggered the global canvas zoom. This distorted the circuit schematic view behind the graph, leading to a frustrating user experience.
- **The Fix**: We established isolated multi-touch gesture listeners restricted exclusively to the Wave Plotter bounds. When the graph area detects multi-touch engagement, the global canvas gesture recognizers are programmatically forced into a locked, passive state. This guarantees that pinch-to-zoom actions are accurately routed solely to the Wave Plotter.
