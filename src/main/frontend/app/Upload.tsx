import { useState } from 'react';

type ImportEvent = {
  status: 'progress' | 'imported' | 'duplicate' | 'failed' | 'complete';
  completed: number;
  total: number;
  name: string;
  trackId: number | null;
  originalId: number | null;
  warnings: string[];
  message: string | null;
  kind?: 'recording' | 'history' | null;
};
type Imported = { id: number; kind: ImportEvent['kind'] };
type Result = ImportEvent & { filename: string };

export function useUpload(onImported: (imported: Imported[]) => void) {
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState('');
  const [results, setResults] = useState<Result[]>([]);
  const [finished, setFinished] = useState(false);

  async function upload(files: File[]) {
    if (!files.length) return;
    setResults([]);
    setFinished(false);
    setUploading(true);
    const imported: Imported[] = [];
    for (const [index, file] of files.entries()) {
      const label = `File ${index + 1} of ${files.length}: ${file.name}`;
      setProgress(`${label}. Uploading and reading GPX…`);
      try {
        if (file.size > 32 * 1024 * 1024)
          throw new Error('Choose a GPX file no larger than 32 MB.');
        await importFile(file, (event) => {
          setProgress(`${label}. ${event.completed} of ${event.total} tracks processed.`);
          if (event.status === 'progress' || event.status === 'complete') return;
          setResults((previous) => [...previous, { ...event, filename: file.name }]);
          if (event.status === 'imported' && event.trackId !== null) {
            imported.push({ id: event.trackId, kind: event.kind });
            onImported([...imported]);
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
    if (imported.length > 0) onImported(imported);
    setUploading(false);
    setFinished(true);
  }
  return { upload, uploading, progress, results, finished };
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
}: {
  uploading: boolean;
  progress: string;
  results: Result[];
  finished: boolean;
}) {
  if (!uploading && !finished) return null;
  const imported = results.filter((result) => result.status === 'imported').length;
  const duplicates = results.filter((result) => result.status === 'duplicate').length;
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
          : `${imported} imported · ${duplicates} duplicate · ${failures} failed · ${warnings} with warnings`}
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
                      ? 'Imported'
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
