import type { PipelineStatus } from '../api/types';

interface Props {
  status: PipelineStatus | null;
  onStop: () => void;
  onResume: () => void;
  busy: boolean;
}

const STATUS_LABEL: Record<PipelineStatus, string> = {
  IDLE: '대기 중',
  RUNNING: '실행 중',
  NEEDS_HUMAN: '확인 필요',
  COMPLETED: '완료',
  STOPPED: '중단됨',
};

export function ControlBar({ status, onStop, onResume, busy }: Props) {
  const canStop = status === 'RUNNING';
  const canResume = status === 'STOPPED' || status === 'NEEDS_HUMAN';

  return (
    <div className="control-bar">
      <span className={`status-badge status-${(status ?? 'IDLE').toLowerCase()}`}>
        {status ? STATUS_LABEL[status] : '확인 중…'}
      </span>
      <div className="control-buttons">
        <button disabled={!canStop || busy} onClick={onStop}>
          중단
        </button>
        <button disabled={!canResume || busy} onClick={onResume}>
          재개
        </button>
      </div>
    </div>
  );
}
