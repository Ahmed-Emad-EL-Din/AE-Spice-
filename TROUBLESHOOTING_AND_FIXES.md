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

## Problem 4: Pre-Splash Main Workspace Bleed-Through
- **The Problem**: Overlapping elements or race conditions during the initial render loop allowed part of the workspace schematic UI to flash or briefly become visible on secondary hardware threads before the splash sequence finished fading in.
- **The Fix**: We replaced parallel render branches in `MainActivity` with a mutual exclusivity construct: `if (showSplash) { SplashScreen(...) } else { SpiceAppUi() }`. This guarantees that the schematic elements cannot compile layout nodes or load into the view tree in any state prior to explicit dismissal.

## Problem 5: Font Scale & Letter Spacing Typography Offsets
- **The Problem**: Hardcoding the pixel dimensions for the custom brand logo's golden-yellow dot caused clipping or misalignment on devices with heavy custom tracking, letters-spacings, or accessibility-increased system font scales.
- **The Fix**: We solved this by using Jetpack Compose's `onTextLayout` with a dot-less base character `"spıce"`. We retrieve the exact bounding box of the target character index dynamically, calculate its coordinate offsets inside a `drawWithContent` canvas modifier, and automatically project the visual yellow circle overlay precisely relative to the glyph's physical pixel boundaries.

