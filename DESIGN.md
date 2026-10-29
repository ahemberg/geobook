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
    fontSize: "24px"
    fontWeight: 700
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Source Sans 3, sans-serif"
    fontSize: "18px"
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
    padding: "6px 0"
  track-row-selected:
    backgroundColor: "{colors.selection}"
  track-detail:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    padding: "12px 16px"
---

# Geobook design

## Overview

The Travel timetable design puts a detailed travel map beside a compact library.
A blue header, white panels and pale blue selection states separate navigation,
saved recordings and the active selection. Source Sans 3 is used throughout.

This document describes the implemented map, recording and history-day controls,
country review, original-export archive, sign-in and account pages. The
[MVP brief](docs/design/mvp.md) records the original design decisions.

## Colors

Blue marks the application and its selections; muted green-gray neutrals support text and separators.

### Primary

The `blue` token belongs to the header, primary buttons and links. `selection` fills selected rows, the current scope label, the active All time button and ordinary button hover states. `blue-hover` belongs to primary button hover.

### Neutral

Use `surface` for panels, `ink` for text, `muted` for supporting text, `line` for separators and `control-border` for button outlines. `map-placeholder` sits beneath map content while tiles load.

### Status and focus

Notices have separate informational, success, error and warning fills. Error and warning text use their matching text tokens. Explicit messages carry the meaning. `focus` marks keyboard focus; focus on the blue header uses white. Destructive controls use `error-text` for text and borders, with `error-background` on hover. Header navigation uses `blue-hover` for hover and the current page.

A track's saved colour connects its swatch and map geometry. Status colours describe application feedback.

## Typography

Source Sans 3 is bundled locally in weights 400, 600 and 700, with a sans-serif fallback. Numbers use tabular figures. Body text inherits the browser's base size; paragraphs use the body line height and stop at 70ch.

The brand uses the brand role; library and recording headings use `title`; first-use copy uses `empty-title`; Recorded travel uses `scope-title`. Track names use 15px text at weight 600 and a 1.25 line height. Supporting text uses `label`; chart axes and map attribution use 12px. On mobile the brand is 25px, the recording heading is 16px and the scope heading is 14px. Point readings contract to 12px on mobile.

## Layout

The app occupies exactly the dynamic viewport height. Desktop has a 56px header and a scope strip at least 56px high. A 304px library sits beside a flexible map column. The track list scrolls independently below its heading and search field. The map takes the remaining vertical space above the selected recording's docked plot. Recording details scroll internally and occupy at most 55% of the map workspace. Upload feedback is bounded to 25dvh and scrolls internally.

Between 701px and 1100px, the library contracts to 260px and playback controls stack beneath the chart. Wider layouts place playback controls and the current point reading beside the chart. The plot is 100px tall on desktop.

At widths up to 700px, the header wraps navigation below the wordmark and upload button, preserving every destination. The scope wraps its heading above the date and gap controls. A 44px library heading and toggle stay above the map. Opening the library covers the map workspace; the covered map and playback are hidden from keyboard and assistive-technology navigation. Selecting a track closes the library and focuses its toggle. Reopening retains the list's scroll position. An empty library opens automatically. The selected plot stays below the map, with an 80px chart and controls underneath. Its panel occupies at most 60% of the remaining workspace and scrolls internally when expanded.

Countries places confirmed summaries beside country-day review on desktop and stacks them on mobile. Date controls and the current scope stay above both views.

The archive replaces the map workspace with a full-width list padded by 24px. File details sit opposite their download and removal actions. On mobile, padding becomes 20px vertically and 16px horizontally, and each row stacks its actions beneath the file details. Long filenames wrap.

## Panels and shadows

The workspace uses flat white panels and single-pixel dividers. The floating fit control and tile-failure notice use the soft shadow `0 2px 8px #20312d24`. Date and gap-setting popovers use `0 8px 32px #20312d30` to separate them from the map. Removal dialogs use a translucent dark blue backdrop to separate the confirmation from the page. Leaflet supplies its own map-control frame. The app declares no custom transitions or animations.

## Shapes

Buttons, form controls and the current scope label share the control radius. Library rows and workspace panels have square corners. Track swatches are circles, 10px across. Buttons and form controls have a minimum height of 44px; Leaflet touch zoom controls are 44px square.

## Components

### Buttons

Ordinary buttons have a white fill and a single-pixel border. Primary buttons use blue; Upload GPX / Takeout uses white with blue text in the header. Hover changes the fill. Disabled upload controls retain their shape at 0.65 opacity with a waiting cursor. Keyboard focus uses a 3px outline offset by 3px. Links stay underlined and thicken their underline on hover.

### Header and scope

The header contains the Geobook wordmark, Map, Countries and Original exports buttons, and Upload GPX / Takeout. Signed-in owners also see Account and Sign out. Navigation uses white text, a filled hover state and `aria-current` for the selected page. The scope strip appears in Map and Countries and contains Recorded travel, a date button showing the applied range or All time, a Recording gaps disclosure, the selected country with an All countries action when scoped, and the matching recorded-track count.

The date button opens a bounded, scrollable panel with labelled native date fields, a year selector, All time, Apply dates and Close dates. Applying or closing returns focus to the date button; Escape also closes it. Dates are inclusive and use local time at each point. All time includes points with unknown dates. Recording gaps opens a matching panel for the saved gap threshold and optional dashed map connections. Form controls use white fills, outlined borders and the shared focus treatment. The upload button opens a hidden native file input.

### Country visits

Confirmed summaries show a country name, distinct recorded days, and first and latest visit dates in divided rows. Selecting a country opens its map extent and filters recorded points while keeping the active dates. All countries clears that country scope.

Review rows keep country, local date and recording evidence together. Native checkboxes support bulk confirmation and exclusion; each row also offers reversible decisions. A state selector separates pending, confirmed and excluded days. Saved totals stay separate from suggestions, with uncertainty and review guidance above the controls.

The skip link targets the visible view. Countries provides a focusable main landmark; Map targets the track library.

### Track rows

Rows contain an independent visibility checkbox, a selection button and a labelled Edit button. Rows have a 56px minimum height; the selection button uses compact vertical padding. Its saved-colour swatch accompanies the name, which is limited to two lines, with the full name retained in the button's accessible text and title. A separate recorded date is omitted when an ISO date or a Swedish or English day-month-year date already appears in the saved name. Other names keep the UTC start-date label; untimed rows show Date unknown. Point counts and the repeated opening instruction are absent. Selection uses the pale blue fill and `aria-pressed`. The swatch stays the same size when names wrap.

Search filters the library by name. Edit expands an inline form with name and native colour inputs, Save changes and Cancel. Visibility saves independently of an editing draft. A failed save keeps the previous saved choice and shows its error beneath the row. Closing the editor restores focus to Edit, or to search if a renamed track no longer matches.

The expanded editor also exposes Delete track. Its confirmation explains that original exports stay in the archive. Successful deletion returns focus to the track library.

### Google history

Google history has a separate collapsible library section, grouped by year.
Collection and year checkboxes control map display while retaining each day's
visibility choice. Rows show a local history date and inferred place names, and
use a striped square swatch to distinguish them from recordings. Search also
matches dates and place names.

History lines are lighter than recorded tracks. Selecting a day opens a details
panel with the local time span, kept and dropped fix counts, named place visits
and original downloads. Selecting a visit pans the map to it. The panel labels
the source as Google history and has no playback controls or elevation plot.

### Sign-in and account

The sign-in page uses a compact form with labelled username and password fields.
When 2FA is enabled, the form also asks for an authenticator or recovery code.
Failed sign-in stays in the form with an error message.

Account has separate sections for changing the password, signing out sessions,
app passwords and 2FA. New app passwords and recovery codes are shown once.
Public mode hides account and sign-out controls.

### Original exports and removal

The archive has a prominent heading and flat rows divided by single-pixel rules. Each row shows its filename, byte count, live track count and associated track names, followed by Download GPX and Preview removal. Loading, empty and retry states use explicit text. Opening the archive focuses its heading.

Track deletion and permanent file removal share a native modal dialog with a white fill, the control radius and 24px padding. It is at most 560px wide, leaves 16px around the viewport and scrolls internally when needed. File removal lists tracks to remove separately from tracks kept through another original, and explains that historical backups remain unchanged.

Cancel receives initial focus. Cancel or Escape returns focus to the triggering button; Escape is disabled while removal is pending. Confirmation uses a labelled destructive button. Pending removal disables both actions and shows a status message. A failure stays in the dialog as an alert and disables confirmation until the dialog is reopened. Successful file removal returns focus to the archive heading.

### Recording details

A compact white panel docks beneath the map. Its header holds the selected recording's name and Close track. The elevation plot, play/pause, four speed choices, elapsed-time reading and current point's local time and recorded elevation remain visible together. The chart itself scrubs elapsed time, replacing the separate slider. It accepts pointer and touch dragging, Left/Right to select adjacent points and Home/End to select the first or last point. Missing elevation breaks the plot; recording gaps appear as shaded intervals. Playback announces skipped gaps and moves a blue marker with a white border while leaving the map camera under manual control.

Point details discloses Previous point, Next point, a numbered input, coordinates and timezone. Recordings without valid timing retain those inspection controls and an explicit playback-unavailable message. Missing local time and elevation have explicit unknown states, with recorded UTC shown when available. Recording details discloses complete point and segment counts, filter and gap guidance, timezone limitations and original-export downloads. Both disclosures retain 44px interaction targets.

### Map and notices

Selected map lines are 5px wide; other lines are 3px and dim to 0.5 opacity while a track is selected. Isolated positions use a 4px circle radius. Fit all tracks floats at the upper right. Keep attribution visible at the bottom.

Upload and loading feedback use full-width notice strips. Tile failure uses a warning above the map attribution with a Retry tiles button. Status messages and alerts retain their accessible roles. First use shows a short explanation and a primary file chooser in the library.

## Design constraints

- Preserve saved track colours across the library and map. Use labelled messages for errors and warnings.
- Keep keyboard focus, map attribution and labelled touch controls visible.
- Keep compact track names readable and the full saved name accessible.
- Keep the map visible while the track list or recording details scroll.
- Use synthetic recordings in public examples and detailed geography in the application.
