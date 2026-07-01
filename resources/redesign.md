# UI Rework: Camera-App-Native Controller & Gallery

## Why

The Controller screen (live viewfinder + shutter controls) currently reads as a tech dashboard: the live feed sits in a small inset card with empty margins around it, and every control (lens switch, rotation calibration, torch, gallery entry, zoom slider, and three separate shutter buttons) lives in a large opaque card stacked *below* the feed. Someone opening this app expects something closer to a stock phone camera app: full-bleed live preview, controls floating directly over the feed, a single shutter button, and a gallery thumbnail tucked in a corner — not a status panel under a small video window.

This rework targets the **Controller** and **Gallery** screens only. The Main menu and Camera Server status screen aren't camera-app-shaped to begin with (the server screen is a status dashboard for a mounted/unattended device — there's nothing to "view" locally), so they're left alone. The existing dark/neon-cyan/purple "glass" visual identity is kept; only the layout and interaction model change.

The backend is **not touched**: `CameraService`, the socket/MJPEG parser in `ControllerConnection`, `NsdHelper`, and the wire protocol all stay exactly as they are. Every control in the new design maps onto a command or state field that already exists.

## Constraint surfaced by this design

`GalleryFile` only carries `name`, `size`, and `mime` — the protocol has no thumbnail-fetch command, only `DOWNLOAD_FILE:<name>` for full files. A gallery grid therefore shows file-type icons + metadata, not real image previews. That's accepted as a known limitation rather than something to work around with new backend surface.

## Command/state surface this reuses (unchanged)

From `ControllerConnection.kt`:
- Commands: `SET_CAMERA:<idx>`, `TOGGLE_TORCH`, `SET_ZOOM:<val>`, `TAKE_PHOTO`, `START_RECORDING`, `STOP_RECORDING`, `PAUSE_STREAM`, `RESUME_STREAM`, `GET_GALLERY`, `DOWNLOAD_FILE:<name>`
- Local-only: `setRotation(rotation: Int)`
- Observed state: `viewfinderState`, `connectionStatus`, `cameraStatus`, `availableCameras`, `activeCameraIndex`, `zoomLimits`, `currentZoom`, `zoomPresets`, `cameraRotation`, `isTorchOn`, `batteryLevel`, `isCharging`, `diskSpace`, `galleryFiles`, `downloadProgress`, `isDownloading`, `downloadError`

## Controller screen

Full-bleed `Box`: the viewfinder image fills the entire screen edge-to-edge (replacing the inset card), with controls overlaid on top of it via top/bottom scrims, instead of stacked in a card below it.

**Top overlay**: back button (left); icon-only rotation-calibration and torch-toggle buttons (right cluster — demoted from labeled pills since they're "set once" controls, not per-shot ones); battery/disk telemetry chips (far right, same data as today, shrunk to fit). The pulsing REC dot stays top-left over the feed. The status text ("Photo Saved!", etc.) becomes a transient auto-dismissing chip instead of a persistent label.

**Bottom overlay**, stacked bottom-up:
- Zoom row: existing preset chips (`.5x/1x/2x/5x`, filtered to `zoomLimits`) plus a slim overlay-styled slider above them — same `SET_ZOOM` command, restyled only.
- Mode switch: a 2-segment PHOTO/VIDEO tap-toggle, purely local UI state (not sent to the server), disabled while recording.
- Shutter row (3 slots): a small square gallery-entry button (left, same `PAUSE_STREAM` + navigate behavior as today); one large circular shutter button (center) that calls `TAKE_PHOTO` in Photo mode or toggles `START_RECORDING`/`STOP_RECORDING` in Video mode, replacing the old three separate buttons; a lens-switch icon button (right, same `SET_CAMERA` cycling as today) with the active lens name as a small caption.

## Gallery screen

`LazyVerticalGrid(columns = GridCells.Fixed(3))` of square tiles instead of a `LazyColumn` of rows — closer to a native photo-picker grid. Each tile: glass background, centered mime-type icon, bottom caption with formatted size, and a corner badge that swaps between download icon / progress spinner / checkmark, matching the three states the old list rows already encoded. Tapping anywhere on the tile (not just a small icon) triggers the download. Toolbar, tabs, empty state, and the instructions dialog are unchanged.

## Files touched

- `app/src/main/java/com/example/remotecamera/ui/controller/ControllerModeScreen.kt`
- `app/src/main/java/com/example/remotecamera/ui/gallery/GalleryScreen.kt`

Not touched: `CameraModeScreen.kt`, `MainScreen.kt`, `Navigation.kt`, `NavigationKeys.kt`, `net/ControllerConnection.kt`, `net/NsdHelper.kt`, `service/CameraService.kt`, theme files.
