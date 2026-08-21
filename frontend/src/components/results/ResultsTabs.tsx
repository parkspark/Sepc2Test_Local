import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { downloadUrl } from '../../api/client';
import type { OutputsResponse } from '../../api/types';
import { TcTable } from './TcTable';

type Tab = 'csv' | 'questions' | 'coverage';

export function ResultsTabs({ outputs }: { outputs: OutputsResponse }) {
  const tabs: { key: Tab; label: string; available: boolean }[] = [
    { key: 'csv', label: 'TC 목록', available: !!outputs.csv?.length },
    { key: 'questions', label: '의문점', available: !!outputs.markdown },
    { key: 'coverage', label: '커버리지 리포트', available: !!outputs.coverage },
  ];
  const firstAvailable = tabs.find((t) => t.available)?.key ?? 'csv';
  const [active, setActive] = useState<Tab>(firstAvailable);

  return (
    <section className="panel">
      <h2>결과</h2>
      <div className="tabs">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            className={`tab-button${active === tab.key ? ' active' : ''}`}
            disabled={!tab.available}
            onClick={() => setActive(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {active === 'csv' && outputs.csv && (
        <>
          <a className="download-link" href={downloadUrl('csv')}>
            ⬇ {outputs.csv_filename} 다운로드
          </a>
          <TcTable rows={outputs.csv} />
        </>
      )}

      {active === 'questions' && outputs.markdown && (
        <>
          <a className="download-link" href={downloadUrl('markdown')}>
            ⬇ {outputs.markdown_filename} 다운로드
          </a>
          <div className="markdown-body">
            <ReactMarkdown>{outputs.markdown}</ReactMarkdown>
          </div>
        </>
      )}

      {active === 'coverage' && outputs.coverage && (
        <div className="markdown-body">
          <ReactMarkdown>{outputs.coverage}</ReactMarkdown>
        </div>
      )}
    </section>
  );
}
