import { useEffect, useState } from 'react';
import { fetchOutputs, resumePipeline, stopPipeline, uploadFiles } from './api/client';
import type { OutputsResponse } from './api/types';
import { useLogStream } from './hooks/useLogStream';
import { useStatusPolling } from './hooks/useStatusPolling';
import { UploadPanel } from './components/UploadPanel';
import { ControlBar } from './components/ControlBar';
import { NeedsHumanBanner } from './components/NeedsHumanBanner';
import { ProgressChecklist } from './components/ProgressChecklist';
import { ConsoleLog } from './components/ConsoleLog';
import { ResultsTabs } from './components/results/ResultsTabs';
import './App.css';

function App() {
  const { status, error: statusError } = useStatusPolling();
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [outputs, setOutputs] = useState<OutputsResponse | null>(null);

  const logsActive = status !== null && status.status !== 'IDLE';
  const { lines } = useLogStream(logsActive);

  useEffect(() => {
    if (status?.status === 'COMPLETED') {
      fetchOutputs().then(setOutputs).catch(() => undefined);
    }
  }, [status?.status]);

  async function handleUpload(pdf: File, csv: File | null) {
    setOutputs(null);
    await uploadFiles(pdf, csv);
  }

  async function handleStop() {
    setBusy(true);
    setActionError(null);
    try {
      await stopPipeline();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function handleResume() {
    setBusy(true);
    setActionError(null);
    try {
      await resumePipeline();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const uploadDisabled = status?.status === 'RUNNING';

  return (
    <div className="app-shell">
      <header className="app-header">
        <h1>Spec2Test</h1>
        <p>기획서 PDF + 참고 TC로 테스트케이스를 자동 생성한다.</p>
      </header>

      {statusError && <p className="error-text">상태 조회 실패: {statusError}</p>}
      {actionError && <p className="error-text">{actionError}</p>}

      <UploadPanel disabled={uploadDisabled} onSubmit={handleUpload} />

      <ControlBar status={status?.status ?? null} onStop={handleStop} onResume={handleResume} busy={busy} />

      {status?.status === 'NEEDS_HUMAN' && <NeedsHumanBanner message={status.message} />}

      {status?.progress && <ProgressChecklist progress={status.progress} />}

      {logsActive && <ConsoleLog lines={lines} />}

      {outputs && <ResultsTabs outputs={outputs} />}
    </div>
  );
}

export default App;
