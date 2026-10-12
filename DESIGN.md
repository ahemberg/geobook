---
name: Geobook
description: Travel timetable for recorded journeys.
colors:
  blue: "#193f72"
  blue-hover: "#28588f"
  selection: "#e5edf8"
  ink: "#20312d"
  muted: "#52645e"
  line: "#d8e1dc"
  control-border: "#9baca4"
  surface: "#fff"
  map-placeholder: "#edf0ee"
  focus: "#2768ba"
  current-page: "#bcd4f5"
  notice: "#eff4fa"
  success: "#eaf3ed"
  error-background: "#fff0ee"
  error-text: "#8a271c"
  warning-background: "#fff7e3"
  warning-text: "#614511"
typography:
  brand:
    fontFamily: "Source Sans 3, sans-serif"
    fontSize: "28px"
    fontWeight: 700
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Source Sans 3, sans-serif"
    fontSize: "22px"
    fontWeight: 600
  empty-title:
    fontFamily: "Source Sans 3, sans-serif"
    fontSize: "21px"
    fontWeight: 600
  scope-title:
    fontFamily: "Source Sans 3, sans-serif"
    fontSize: "18px"
    fontWeight: 600
  body:
    fontFamily: "Source Sans 3, sans-serif"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Source Sans 3, sans-serif"
    fontSize: "14px"
    fontWeight: 400
rounded:
  control: "6px"
  row: "0"
  swatch: "50%"
spacing:
  compact: "8px"
  small: "12px"
  medium: "16px"
  library: "20px"
  panel: "24px"
components:
  button:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "10px 14px"
  button-hover:
    backgroundColor: "{colors.selection}"
  button-primary:
    backgroundColor: "{colors.blue}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    padding: "10px 14px"
  button-primary-hover:
    backgroundColor: "{colors.blue-hover}"
  button-upload:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.blue}"
    rounded: "{rounded.control}"
    padding: "10px 14px"
  scope-value:
    backgroundColor: "{colors.selection}"
    textColor: "{colors.blue}"
    rounded: "{rounded.control}"
    padding: "7px 14px"
  track-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.row}"
    padding: "14px 0"
  track-row-selected:
    backgroundColor: "{colors.selection}"
  track-detail:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    padding: "22px 24px"
---

# Design System: Geobook

## Overview

**Creative North Star: "Travel timetable"**

Geobook puts a detailed travel map beside a compact library. A blue header anchors the page; white panels and pale blue selection keep saved recordings easy to scan. Source Sans 3 carries headings, controls and recording details.

This system records the rendered upload, date-filter, track-library controls and point-inspection implementation. The [approved MVP brief](docs/design/mvp.md) remains the authority for later country filters and playback.

**Key Characteristics:**

- Map beside the library on desktop, above it on mobile.
- Saved track colours connect library swatches and map geometry.
- Flat panels, visible dividers and labelled controls.

## Colors

Blue marks the application and its selections; muted green-gray neutrals support text and separators.

### Primary

The `blue` token belongs to the header, primary buttons and links. `selection` fills selected rows, the current scope label, the active All time button and ordinary button hover states. `blue-hover` belongs to primary button hover.

### Neutral

Use `surface` for panels, `ink` for text, `muted` for supporting text, `line` for separators and `control-border` for button outlines. `map-placeholder` sits beneath map content while tiles load.

### Status and focus

Notices have separate informational, success, error and warning fills. Error and warning text use their matching text tokens. Explicit messages carry the meaning. `focus` marks keyboard focus; focus on the blue header uses white. `current-page` underlines Map in the desktop header.

**The Saved Colour Rule.** A track's saved colour connects its swatch and map geometry; status colours describe application feedback.

## Typography

Source Sans 3 is bundled locally in weights 400, 600 and 700, with a sans-serif fallback. Numbers use tabular figures. Body text inherits the browser's base size; paragraphs use the body line height and stop at 70ch.

The brand is the largest text. Library and recording headings use `title`; first-use copy uses `empty-title`; Recorded travel uses `scope-title`. Track names and control emphasis use weight 600. Supporting text uses `label`. Map attribution uses 12px. On mobile the brand drops to 25px and the scope heading to 16px.

## Layout

The app fills at least the dynamic viewport height. Desktop has a 76px header and a scope strip at least 70px high. A 300px library sits beside a flexible map column. The map has a 460px minimum height, with the selected recording below it. Panels grow with content; long names wrap.

At widths up to 700px, the header becomes 64px high and the scope strip has a 64px minimum height, wrapping as needed. Date controls start collapsed behind a Date filters button; the current scope stays visible. The map moves above the library and its minimum height becomes 48dvh. The selected recording stays beneath the map. The library starts collapsed behind a labelled toggle; the first-use upload prompt remains visible. Map in the header is hidden. Common horizontal padding contracts to 16px.

## Elevation & Depth

The workspace uses flat white panels and single-pixel dividers. Only the floating fit control and tile-failure notice use the application's soft shadow, `0 2px 8px #20312d24`. Leaflet supplies its own map-control frame. The app declares no custom transitions or animations.

## Shapes

Buttons, form controls and the current scope label share the control radius. Library rows and workspace panels have square corners. Track swatches are circles, 10px across. Buttons and form controls have a minimum height of 44px; Leaflet touch zoom controls are 44px square.

## Components

### Buttons

Ordinary buttons have a white fill and a single-pixel border. Primary buttons use blue; Upload GPX uses white with blue text in the header. Hover changes the fill. Disabled upload controls retain their shape at 0.65 opacity with a waiting cursor. Keyboard focus uses a 3px outline offset by 3px. Links stay underlined and thicken their underline on hover.

### Header and scope

The header contains the Geobook wordmark, a static Map label and Upload GPX aligned to the right. The scope strip contains Recorded travel, the applied date range or All time, and the matching track count. Below it, labelled native date fields apply an inclusive range using local time at each point. A year selector applies a full calendar year; All time clears the range and includes points with unknown dates. Form controls use white fills, outlined borders and the shared focus treatment. The upload button opens a hidden native file input.

### Track rows

Rows contain an independent visibility checkbox, a selection button and a labelled Edit button. The selection button contains the saved-colour swatch, wrapping track name, recorded date and recorded or matching point count. Untimed rows show Date unknown and the track ID. Rows offer to open the complete recording and identify points with unknown dates. Selection uses the pale blue fill and `aria-pressed`. The swatch stays the same size when names wrap.

Search filters the library by name. Edit expands an inline form with name and native colour inputs, Save changes and Cancel. Visibility saves independently of an editing draft. A failed save keeps the previous saved choice and shows its error beneath the row. Closing the editor restores focus to Edit, or to search if a renamed track no longer matches.

### Recording details

A white panel beneath the map contains a heading, complete point and segment counts, gap guidance and original-export downloads. It explains that the recording includes points outside the date filter and that hidden tracks stay off the map. Previous point, Next point and a numbered input inspect each point's local time, timezone, coordinates and recorded elevation, with explicit unknown values. Close track floats right on desktop and precedes the heading on mobile. This panel has no playback or elevation plot yet.

### Map and notices

Selected map lines are 5px wide; other lines are 3px and dim to 0.5 opacity while a track is selected. Isolated positions use a 4px circle radius. Fit all tracks floats at the upper right. Keep attribution visible at the bottom.

Upload and loading feedback use full-width notice strips. Tile failure uses a warning above the map attribution with a Retry tiles button. Status messages and alerts retain their accessible roles. First use shows a short explanation and a primary file chooser in the library.

## Do's and Don'ts

### Do:

- Do preserve the saved track colour across the library and map.
- Do keep keyboard focus, map attribution and labelled touch controls visible.
- Do let track names wrap and keep supporting counts readable.
- Do use synthetic recordings in public examples.

### Don't:

- Don't treat planned country filters or playback as existing components.
- Don't substitute schematic geography for the application's detailed map.
- Don't use track colour alone to communicate an error or warning.
