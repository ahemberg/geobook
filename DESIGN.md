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

This system records the rendered upload, date-filter, track-library controls, point inspection, playback, elevation plot, gap settings and original-export archive, including confirmed removal. The [approved MVP brief](docs/design/mvp.md) remains the authority for later country filters.

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

Notices have separate informational, success, error and warning fills. Error and warning text use their matching text tokens. Explicit messages carry the meaning. Destructive controls use `error-text` for text and borders, with `error-background` on hover. `focus` marks keyboard focus; focus on the blue header uses white. Header navigation uses `blue-hover` for hover and the current page.

**The Saved Colour Rule.** A track's saved colour connects its swatch and map geometry; status colours describe application feedback.

## Typography

Source Sans 3 is bundled locally in weights 400, 600 and 700, with a sans-serif fallback. Numbers use tabular figures. Body text inherits the browser's base size; paragraphs use the body line height and stop at 70ch.

The brand is the largest text. Library and recording headings use `title`; first-use copy uses `empty-title`; Recorded travel uses `scope-title`. Track names and control emphasis use weight 600. Supporting text uses `label`. Map attribution uses 12px. On mobile the brand drops to 25px and the scope heading to 16px.

## Layout

The app fills at least the dynamic viewport height. Desktop has a 76px header and a scope strip at least 70px high. A 300px library sits beside a flexible map column. The map has a 460px minimum height, with the selected recording below it. Panels grow with content; long names wrap.

At widths up to 700px, the header grows with its content, with a 64px minimum height. Navigation wraps onto a full-width row below the brand and upload button; both destinations remain visible. The scope strip has a 64px minimum height, wrapping as needed. Date controls start collapsed behind a Date filters button; the current scope stays visible. The map moves above the library and its minimum height becomes 48dvh. The selected recording stays beneath the map. The library starts collapsed behind a labelled toggle; the first-use upload prompt remains visible. Common horizontal padding contracts to 16px.

The archive replaces the map workspace with a full-width list padded by 24px. File details sit opposite their download and removal actions. On mobile, padding becomes 20px vertically and 16px horizontally, and each row stacks its actions beneath the file details. Long filenames wrap.

## Elevation & Depth

The workspace uses flat white panels and single-pixel dividers. Only the floating fit control and tile-failure notice use the application's soft shadow, `0 2px 8px #20312d24`. Removal dialogs use a translucent dark blue backdrop to separate the confirmation from the page. Leaflet supplies its own map-control frame. The app declares no custom transitions or animations.

## Shapes

Buttons, form controls and the current scope label share the control radius. Library rows and workspace panels have square corners. Track swatches are circles, 10px across. Buttons and form controls have a minimum height of 44px; Leaflet touch zoom controls are 44px square.

## Components

### Buttons

Ordinary buttons have a white fill and a single-pixel border. Primary buttons use blue; Upload GPX uses white with blue text in the header. Hover changes the fill. Disabled upload controls retain their shape at 0.65 opacity with a waiting cursor. Keyboard focus uses a 3px outline offset by 3px. Links stay underlined and thicken their underline on hover.

### Header and scope

The header contains the Geobook wordmark, Map and Original exports navigation buttons, and Upload GPX aligned to the right. Navigation uses white text, a filled hover state and `aria-current` for the selected page. The scope strip appears in the map view and contains Recorded travel, the applied date range or All time, and the matching track count. Below it, labelled native date fields apply an inclusive range using local time at each point. A year selector applies a full calendar year; All time clears the range and includes points with unknown dates. Form controls use white fills, outlined borders and the shared focus treatment. The upload button opens a hidden native file input.

### Track rows

Rows contain an independent visibility checkbox, a selection button and a labelled Edit button. The selection button contains the saved-colour swatch, wrapping track name, recorded date and recorded or matching point count. Untimed rows show Date unknown and the track ID. Rows offer to open the complete recording and identify points with unknown dates. Selection uses the pale blue fill and `aria-pressed`. The swatch stays the same size when names wrap.

Search filters the library by name. Edit expands an inline form with name and native colour inputs, Save changes and Cancel. Visibility saves independently of an editing draft. A failed save keeps the previous saved choice and shows its error beneath the row. Closing the editor restores focus to Edit, or to search if a renamed track no longer matches.

The expanded editor also exposes Delete track. Its confirmation explains that original exports stay in the archive. Successful deletion returns focus to the track library.

### Original exports and removal

The archive has a prominent heading and flat rows divided by single-pixel rules. Each row shows its filename, byte count, live track count and associated track names, followed by Download GPX and Preview removal. Loading, empty and retry states use explicit text. Opening the archive focuses its heading.

Track deletion and permanent file removal share a native modal dialog with a white fill, the control radius and 24px padding. It is at most 560px wide, leaves 16px around the viewport and scrolls internally when needed. File removal lists tracks to remove separately from tracks kept through another original, and explains that historical backups remain unchanged.

Cancel receives initial focus. Cancel or Escape returns focus to the triggering button; Escape is disabled while removal is pending. Confirmation uses a labelled destructive button. Pending removal disables both actions and shows a status message. A failure stays in the dialog as an alert and disables confirmation until the dialog is reopened. Successful file removal returns focus to the archive heading.

### Recording details

A white panel beneath the map contains a heading, complete point and segment counts, gap guidance and original-export downloads. It explains that the recording includes points outside the date filter and that hidden tracks stay off the map. Previous point, Next point and a numbered input inspect each point's local time, timezone, coordinates and recorded elevation, with explicit unknown values. Close track floats right on desktop and precedes the heading on mobile. Playback adds play and pause, four speed choices and an elapsed-time slider. The elevation plot uses the saved track colour, labelled axes and a selected-point marker; pointer and touch dragging select recorded points. Missing elevation breaks the plot, and recording gaps show shaded intervals with their duration. Playback moves a blue marker with a white border while the map camera stays under manual control. A collapsible gap-settings form beneath the date filter saves the gap threshold and optional dashed map connections.

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

- Don't treat planned country filters as existing components.
- Don't substitute schematic geography for the application's detailed map.
- Don't use track colour alone to communicate an error or warning.
