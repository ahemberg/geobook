import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';

export default function RemovalDialog({
  title,
  children,
  action,
  remove,
  cancel,
}: {
  title: string;
  children: ReactNode;
  action: string;
  remove: () => Promise<void>;
  cancel: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    cancelButton.current?.focus();
    return () => element?.close();
  }, []);
  async function confirm() {
    setBusy(true);
    setError('');
    try {
      await remove();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Removal failed. Close and try again.');
      setBusy(false);
    }
  }
  return (
    <dialog
      ref={dialog}
      className="removal-dialog"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) cancel();
      }}
    >
      <h2 id={titleId}>{title}</h2>
      {children}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {busy && <p role="status">Removing…</p>}
      <div className="editor-actions">
        <button ref={cancelButton} disabled={busy} onClick={cancel}>
          Cancel
        </button>
        <button
          className="danger"
          disabled={busy || !!error}
          onClick={() => {
            void confirm();
          }}
        >
          {action}
        </button>
      </div>
    </dialog>
  );
}
