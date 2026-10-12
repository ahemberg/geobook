import { useEffect, useRef, useState } from 'react';
import { request } from './api';
import type { OriginalExport, RemovalPreview } from './api';
import RemovalDialog from './RemovalDialog';

export default function OriginalArchive({
  reload,
  onRemoved,
}: {
  reload: number;
  onRemoved: () => void;
}) {
  const [originals, setOriginals] = useState<OriginalExport[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const [preview, setPreview] = useState<RemovalPreview | null>(null);
  const [previewing, setPreviewing] = useState<number | null>(null);
  const trigger = useRef<HTMLButtonElement | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    heading.current?.focus();
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    request<OriginalExport[]>('/api/originals', { signal: controller.signal })
      .then((items) => {
        if (controller.signal.aborted) return;
        setOriginals(items);
        setLoading(false);
        setError('');
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        setLoading(false);
        setError('Original exports could not be loaded. Retry to refresh the archive.');
      });
    return () => controller.abort();
  }, [reload, retry]);
  async function openPreview(original: OriginalExport, button: HTMLButtonElement) {
    trigger.current = button;
    setPreviewing(original.id);
    setError('');
    try {
      setPreview(await request<RemovalPreview>(`/api/originals/${original.id}/removal`));
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : 'The removal preview could not be loaded. Try again.',
      );
    } finally {
      setPreviewing(null);
    }
  }
  function cancel() {
    setPreview(null);
    trigger.current?.focus();
  }
  return (
    <main className="archive" id="originals">
      <h1 ref={heading} tabIndex={-1}>
        Original exports
      </h1>
      <p>Exact files you uploaded, including exports whose tracks you deleted from the library.</p>
      <p className="hint">
        Removing a file affects live Geobook data only. Historical backups are unchanged. This does
        not securely erase copies from storage.
      </p>
      {loading && <p role="status">Loading original exports…</p>}
      {error && (
        <div className="notice error" role="alert">
          {error}
          <button onClick={() => setRetry((value) => value + 1)}>Retry archive</button>
        </div>
      )}
      {!loading && !error && originals.length === 0 && <p>No original exports retained.</p>}
      <ul className="archive-list">
        {originals.map((original) => (
          <li key={original.id}>
            <div className="archive-file">
              <h2>{original.filename}</h2>
              <p className="hint">
                {original.byteCount.toLocaleString()} bytes · {original.tracks.length} live tracks
              </p>
              {original.tracks.length > 0 && (
                <ul>
                  {original.tracks.map((track) => (
                    <li key={track.id}>{track.name}</li>
                  ))}
                </ul>
              )}
            </div>
            <div className="archive-actions">
              <a
                href={`/api/originals/${original.id}`}
                aria-label={`Download ${original.filename}`}
              >
                Download GPX
              </a>
              <button
                className="danger"
                disabled={previewing !== null}
                aria-label={`Preview removal of ${original.filename}`}
                onClick={(event) => {
                  void openPreview(original, event.currentTarget);
                }}
              >
                {previewing === original.id ? 'Loading preview…' : 'Preview removal'}
              </button>
            </div>
          </li>
        ))}
      </ul>
      {preview && (
        <RemovalDialog
          title={`Permanently remove ${preview.filename}?`}
          action="Permanently remove file"
          cancel={cancel}
          remove={async () => {
            await request(`/api/originals/${preview.id}`, {
              method: 'DELETE',
              headers: { 'If-Match': preview.revision },
            });
            setOriginals((items) => items.filter((item) => item.id !== preview.id));
            setPreview(null);
            onRemoved();
            heading.current?.focus();
          }}
        >
          <p>The entire original file will be removed. This cannot be undone in Geobook.</p>
          <AffectedTracks
            tracks={preview.tracks.filter((track) => !track.retained)}
            title="Tracks to remove"
          />
          <AffectedTracks
            tracks={preview.tracks.filter((track) => track.retained)}
            title="Tracks kept through another original"
          />
          <p className="hint">
            Tracks kept through another original retain their saved choices. Historical backups are
            unchanged. Removal does not promise secure erasure.
          </p>
        </RemovalDialog>
      )}
    </main>
  );
}

function AffectedTracks({ tracks, title }: { tracks: RemovalPreview['tracks']; title: string }) {
  return (
    <section aria-label={title}>
      <h3>{title}</h3>
      {tracks.length ? (
        <ul>
          {tracks.map((track) => (
            <li key={track.id}>{track.name}</li>
          ))}
        </ul>
      ) : (
        <p>None.</p>
      )}
    </section>
  );
}
