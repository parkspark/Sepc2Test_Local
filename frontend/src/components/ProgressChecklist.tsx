import type { Progress, ProgressItem } from '../api/types';

const ICON: Record<ProgressItem['status'], string> = {
  done: '✅',
  running: '⏳',
  blocked: '⚠️',
  pending: '⬜',
};

function PhaseList({ title, items }: { title: string; items: ProgressItem[] }) {
  if (items.length === 0) {
    return null;
  }
  return (
    <div className="phase-list">
      <h3>{title}</h3>
      <ul>
        {items.map((item, i) => (
          <li key={i} className={`phase-item status-${item.status}`}>
            <span className="phase-icon">{ICON[item.status]}</span>
            <span>{item.text}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function ProgressChecklist({ progress }: { progress: Progress }) {
  return (
    <section className="panel">
      <h2>진행 상황</h2>
      <PhaseList title="Phase 0 — 초기화" items={progress.phase0} />
      <PhaseList title="Phase 1 — 섹션별 TC 생성" items={progress.phase1} />
      <PhaseList title="Phase 2 — 병합·최종 검증" items={progress.phase2} />
    </section>
  );
}
