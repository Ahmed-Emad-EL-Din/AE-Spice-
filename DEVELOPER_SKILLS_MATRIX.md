# Developer Skills and Competency Matrix

## 1. Core Competencies Required
- **Advanced Pointer Input Handling**: Mastery of low-level Android gesture detection is essential. Developers must understand pointer tracking, multi-touch coordinate parsing, velocity calculations, and custom event interception/consumption to build fluid, conflict-free workspaces.
- **Layout State Serialization**: Proficiency in converting complex UI layout structures (dimensions, coordinates, visibility) into lightweight, serializable data objects. This must be accomplished for local storage persistence seamlessly, without blocking the main UI thread during read/write lifecycles.
- **High-Precision Typography Bounds & Offset Calculations**: Skill in intercepting raw layout metrics from Jetpack Compose's `TextLayoutResult` or Android's `StaticLayout`. Understanding character bounding boxes, multi-line offsets, baseline alignment, and deriving physical glyph positions (such as x-height and cap-height) to overlay pixel-perfect visual accents on text independent of system font sizes or user scaling boundaries.
- **Dynamic Window & Viewport Management**: Expertise in custom layout viewport arithmetic. Developers must be capable of calculating collision boundaries, enforcing rendering guardrails, and managing dynamic multi-axis window positioning within restricted hardware screens.

## 2. Framework Alignment
Implementing these architectural patterns requires deep alignment with modern Android frameworks:
- In Jetpack Compose, this involves mastery of `Modifier.pointerInput`, `detectDragGestures`, `awaitPointerEventScope`, and state hoisting for coordinate tracking.
- In legacy Android View/XML systems, it requires overriding `onInterceptTouchEvent` and `onTouchEvent`, meticulously managing `MotionEvent.ACTION_DOWN/MOVE/UP`, and manipulating `View.offsetTopAndBottom` or translation properties.

## 3. Verification & Performance Benchmarks
- **Smooth Transitions**: All custom gesture handling and UI repositioning must maintain hardware-accelerated transitions, operating at a strict minimum performance baseline of 60 frames per second (fps). Drop frames or jank during window dragging are unacceptable.
- **Resource Management**: Engineers must validate that zero interaction memory leaks occur. Rapid multi-window repositioning or real-time graph updates must not result in rendering micro-stutters, unbounded state recompositions, or excessive garbage collection overhead.
