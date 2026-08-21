import { useRef, useState } from 'react';

interface Props {
  disabled: boolean;
  onSubmit: (pdf: File, csv: File | null) => Promise<void>;
}

function DropZone({
  label,
  hint,
  accept,
  file,
  disabled,
  onPick,
}: {
  label: string;
  hint: string;
  accept: string;
  file: File | null;
  disabled: boolean;
  onPick: (file: File | null) => void;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [dragOver, setDragOver] = useState(false);

  return (
    <div
      className={`drop-zone${dragOver ? ' drag-over' : ''}${disabled ? ' disabled' : ''}`}
      onClick={() => !disabled && inputRef.current?.click()}
      onDragOver={(e) => {
        e.preventDefault();
        if (!disabled) setDragOver(true);
      }}
      onDragLeave={() => setDragOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragOver(false);
        if (disabled) return;
        const dropped = e.dataTransfer.files?.[0];
        if (dropped) onPick(dropped);
      }}
    >
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        hidden
        disabled={disabled}
        onChange={(e) => onPick(e.target.files?.[0] ?? null)}
      />
      <div className="drop-zone-label">{label}</div>
      <div className="drop-zone-hint">{file ? file.name : hint}</div>
    </div>
  );
}

export function UploadPanel({ disabled, onSubmit }: Props) {
  const [pdf, setPdf] = useState<File | null>(null);
  const [csv, setCsv] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleStart() {
    if (!pdf) {
      setError('기획서 PDF를 선택하라.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit(pdf, csv);
      setPdf(null);
      setCsv(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="panel">
      <h2>업로드</h2>
      <div className="drop-zone-row">
        <DropZone
          label="기획서 PDF (필수)"
          hint="드래그하거나 클릭해서 선택"
          accept="application/pdf"
          file={pdf}
          disabled={disabled}
          onPick={setPdf}
        />
        <DropZone
          label="참고 TC CSV (선택)"
          hint="생략 시 이전 참고 CSV 재사용"
          accept=".csv,.xlsx"
          file={csv}
          disabled={disabled}
          onPick={setCsv}
        />
      </div>
      {error && <p className="error-text">{error}</p>}
      <button className="primary-button" disabled={disabled || submitting} onClick={handleStart}>
        {submitting ? '시작 중…' : '분석 시작'}
      </button>
    </section>
  );
}
