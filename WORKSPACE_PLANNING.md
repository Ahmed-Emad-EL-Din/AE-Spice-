# Workspace Planning and Architecture

## 1. Executive Overview
The primary design goal for the AE Spice Studio workspace is to shift from a cramped, rigid 45% split-screen panel system to a 100% full-screen immersive canvas. This transition aims to maximize the user's focus on the circuit schematic, providing a seamless, expansive area for design while keeping necessary tools accessible but unobtrusive.

## 2. Component & Layout Architecture
- **The "Waveform" Trigger**: The legacy "Reveal Wave Plotter" trigger, which previously occupied significant screen real estate, is being converted into a compact, floating action component named "Waveform". This trigger will be visually subtle, allowing users to summon the plotter only when needed without disrupting the workspace.
- **The "Components" Picker**: The verbally heavy "Electrical Component Catalog" is being refactored into a high-density, search-first panel simply named "Components". All instructional text and boilerplate headers have been removed. The focus is placed entirely on the filter input and the direct results list to optimize vertical and horizontal screen real estate.

## 3. Dynamic Customization Blueprint
- **Freeform Dragging**: To enhance user control, 1-finger drag handles will be implemented on all floating panels and the Waveform trigger. This allows users to position tools anywhere on the canvas that best suits their current workflow.
- **Adaptive Edge Snapping**: To maintain a tidy workspace, floating panels will feature adaptive edge snapping. When a panel is dragged within a 20px threshold of any viewport edge (Top, Bottom, Left, Right), it will automatically anchor smoothly to that edge, creating a structured layout without manual micro-adjustments.

## 4. State Persistence Map
To preserve the custom layout across sessions, precise (x, y) coordinates of all floating elements will be captured upon gesture release (`onTouchEnd`). This spatial data will be serialized and stored via an Android local storage mechanism (e.g., DataStore or SharedPreferences). Upon app reboot, the UI engine will deserialize this data and restore the workspace to its exact prior state, ensuring layout persistence.

## 5. Synchronized Boot & Stylized Branding Logo
- **Atomic Initialization**: Prevent the main application UI tree (`SpiceAppUi`) from loading or laying out in parallel with the introduction sequence. A strict blocking binary state ensures the splash screen has complete dominance of the render tree. Following the completion of the introduction timer, the states are flipped, swapping the splash hierarchy out with the main workspace without memory leaks.
- **Dynamic Identity Overlay**: Re-draw the typography of the branding logo dynamically. Rather than standard static glyph rendering, intercept the local text layout bounds. The character 'i' is represented as a dot-less glyph, and a custom light emitter golden-yellow circle is drawn atop using measured coordinates, ensuring typography consistency across different system-level font scales.

