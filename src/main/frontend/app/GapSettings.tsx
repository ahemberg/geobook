import { useState } from 'react';
import { request } from './api';
import type { PlaybackSettings } from './api';

export default function GapSettings({
  settings,
  saved,
}: {
  settings: PlaybackSettings;
  saved: (settings: PlaybackSettings) => void;
}) {
  const [previous, setPrevious] = useState(settings);
  const [seconds, setSeconds] = useState(String(settings.gapSeconds));
  const [connections, setConnections] = useState(settings.showGapConnections);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  // Refresh only fields the user has not edited.
  if (
    settings.gapSeconds !== previous.gapSeconds ||
    settings.showGapConnections !== previous.showGapConnections
  ) {
    setPrevious(settings);
    if (seconds === String(previous.gapSeconds)) setSeconds(String(settings.gapSeconds));
    if (connections === previous.showGapConnections) setConnections(settings.showGapConnections);
  }
  async function save() {
    setSaving(true);
    setMessage('');
    try {
      const result = await request<PlaybackSettings>('/api/playback-settings', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ gapSeconds: Number(seconds), showGapConnections: connections }),
      });
      saved(result);
      setMessage('Gap settings saved.');
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : 'Settings could not be saved. Try again.',
      );
    } finally {
      setSaving(false);
    }
  }
  return (
    <details className="gap-settings">
      <summary>Recording gaps</summary>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <label>
          Gap threshold in seconds
          <input
            type="number"
            required
            min={1}
            max={2147483647}
            step={1}
            value={seconds}
            disabled={saving}
            onChange={(event) => setSeconds(event.target.value)}
          />
        </label>
        <label className="gap-connections">
          <input
            type="checkbox"
            checked={connections}
            disabled={saving}
            onChange={(event) => setConnections(event.target.checked)}
          />
          Show gray dashed gap connections
        </label>
        <button disabled={saving}>{saving ? 'Saving…' : 'Save gap settings'}</button>
      </form>
      <p className="hint">
        Connections mark missing recording, not recorded travel or evidence of a country visit.
        Source segments always stay separate.
      </p>
      {message && <p role="status">{message}</p>}
    </details>
  );
}
