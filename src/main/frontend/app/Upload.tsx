import { useState } from 'react';

type ImportEvent = {
  kind?: 'recording' | 'history';
  status: 'progress' | 'imported' | 'updated' | 'unchanged' | 'duplicate' | 'failed' | 'complete';
  completed: number;
  total: number;
  name: string;
  trackId: number | null;
  originalId: number | null;
  warnings: string[];
  message: string | null;
  summary?: HistorySummary | null;
};
type HistorySummary = { created: number; updated: number; unchanged: number };
type Result = ImportEvent & { filename: string };

export function useUpload(
  onImported: (trackIds: number[], recordingId: number | null) => void,
  uploadLimitMb?: number,
) {
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState('');
  const [results, setResults] = useState<Result[]>([]);
  const [finished, setFinished] = useState(false);
  const [historySummary, setHistorySummary] = useState<HistorySummary | null>(null);

  async function upload(files: File[]) {
    if (!files.length) return;
    setResults([]);
    setHistorySummary(null);
    setFinished(false);
    setUploading(true);
    const imported: number[] = [];
    let recordingId: number | null = null;
    for (const [index, file] of files.entries()) {
      const label = `File ${index + 1} of ${files.length}: ${file.name}`;
      setProgress(`${label}. Uploading and reading export…`);
      try {
        if (uploadLimitMb !== undefined && file.size > uploadLimitMb * 1024 * 1024)
          throw new Error(`Upload exceeds the ${uploadLimitMb} MB limit. Choose a smaller file.`);
        await importFile(file, (event) => {
          setProgress(`${label}. ${event.completed} of ${event.total} items processed.`);
          setHistorySummary((previous) => addSummary(previous, event));
          if (event.status === 'progress' || event.status === 'complete') return;
          setResults((previous) => [...previous, { ...event, filename: file.name }]);
          if (
            (event.status === 'imported' || event.status === 'updated') &&
            event.trackId !== null
          ) {
            imported.push(event.trackId);
            recordingId = imported.length === 1 && event.kind !== 'history' ? event.trackId : null;
            onImported([...imported], recordingId);
          }
        });
      } catch (error) {
        setResults((previous) => [
          ...previous,
          {
            filename: file.name,
            name: file.name,
            status: 'failed',
            completed: 0,
            total: 0,
            trackId: null,
            originalId: null,
            warnings: [],
            message: error instanceof Error ? error.message : 'Upload failed. Please try again.',
          },
        ]);
      }
    }
    if (imported.length > 0) onImported(imported, recordingId);
    setUploading(false);
    setFinished(true);
  }
  return { upload, uploading, progress, results, finished, historySummary };
}

function addSummary(previous: HistorySummary | null, event: ImportEvent) {
  if (event.status !== 'complete' || !event.summary) return previous;
  return {
    created: (previous?.created ?? 0) + event.summary.created,
    updated: (previous?.updated ?? 0) + event.summary.updated,
    unchanged: (previous?.unchanged ?? 0) + event.summary.unchanged,
  };
}

async function importFile(file: File, onEvent: (event: ImportEvent) => void) {
  const body = new FormData();
  body.append('file', file);
  const response = await fetch('/api/imports', { method: 'POST', body });
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(error?.message ?? 'Upload failed. Please try again.');
  }
  if (!response.body)
    throw new Error('Import progress is unavailable. Reload to check saved tracks.');
  await readProgress(response.body, onEvent);
}

async function readProgress(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: ImportEvent) => void,
) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let pending = '';
  let complete = false;
  try {
    while (true) {
      const { done, value } = await reader.read();
      pending += decoder.decode(value, { stream: !done });
      const lines = pending.split('\n');
      pending = lines.pop() ?? '';
      for (const line of lines) {
        const event = JSON.parse(line) as ImportEvent;
        complete = event.status === 'complete' || (event.status === 'failed' && event.total === 0);
        onEvent(event);
      }
      if (done) break;
    }
    if (!complete || pending.trim())
      throw new Error(
        'The connection ended before import finished. Reload to check saved tracks; uploading again will skip duplicates.',
      );
  } finally {
    reader.releaseLock();
  }
}

export function UploadFeedback({
  uploading,
  progress,
  results,
  finished,
  historySummary,
}: {
  uploading: boolean;
  progress: string;
  results: Result[];
  finished: boolean;
  historySummary: HistorySummary | null;
}) {
  if (!uploading && !finished) return null;
  const recordings = results.filter((result) => result.kind !== 'history');
  const imported = recordings.filter((result) => result.status === 'imported').length;
  const summary = historySummary
    ? `Google history: ${historySummary.created} created · ${historySummary.updated} updated · ${historySummary.unchanged} unchanged${imported ? ` · ${imported} recordings imported` : ''}`
    : `${imported} imported`;
  // The history summary already counts duplicate days as unchanged.
  const duplicates = recordings.filter((result) => result.status === 'duplicate').length;
  const failures = results.filter((result) => result.status === 'failed').length;
  const warnings = results.filter((result) => result.warnings.length > 0).length;
  const originals = new Map(
    results.flatMap((result) =>
      result.originalId === null ? [] : [[result.originalId, result.filename] as const],
    ),
  );
  return (
    <section className="import-feedback" aria-label="Import results">
      <div className="notice" role="status">
        {uploading
          ? progress
          : `${summary} · ${duplicates} duplicate · ${failures} failed · ${warnings} with warnings`}
      </div>
      {results.length > 0 && (
        <details className="import-details" open={failures > 0}>
          <summary>Review import results</summary>
          <div className="import-result-list">
            <div className="original-downloads">
              {[...originals].map(([id, filename]) => (
                <a key={id} href={`/api/originals/${id}`}>
                  Download original export: {filename}
                </a>
              ))}
            </div>
            <ul>
              {results.map((result, index) => (
                <li key={index}>
                  <p
                    className={result.status === 'failed' ? 'import-failure' : ''}
                    role={result.status === 'failed' ? 'alert' : undefined}
                  >
                    {result.filename} · {result.name}:{' '}
                    {result.status === 'imported'
                      ? result.kind === 'history'
                        ? 'Created'
                        : 'Imported'
                      : result.status === 'updated'
                        ? 'Updated'
                        : result.status === 'unchanged'
                          ? 'Unchanged'
                          : result.status === 'duplicate'
                            ? 'Duplicate'
                            : 'Failed'}
                    {result.message && `. ${result.message}`}
                  </p>
                  {result.warnings.map((warning) => (
                    <p className="import-warning" key={warning}>
                      Warning: {warning}
                    </p>
                  ))}
                </li>
              ))}
            </ul>
          </div>
        </details>
      )}
    </section>
  );
}
