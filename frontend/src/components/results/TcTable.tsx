import { useMemo, useState } from 'react';
import type { TcRow } from '../../api/types';

const PAGE_SIZE = 25;
const FILTER_COLUMNS = ['대분류', '중분류', '소분류'] as const;
const COLUMNS = ['No', '대분류', '중분류', '소분류', '테스트 항목', '사전조건', '테스트 스텝', '기대결과', '비고'];

interface Props {
  rows: TcRow[];
}

export function TcTable({ rows }: Props) {
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [page, setPage] = useState(1);

  const filterOptions = useMemo(() => {
    const options: Record<string, string[]> = {};
    for (const col of FILTER_COLUMNS) {
      options[col] = Array.from(new Set(rows.map((r) => r[col]).filter(Boolean))).sort();
    }
    return options;
  }, [rows]);

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return rows.filter((row) => {
      for (const col of FILTER_COLUMNS) {
        if (filters[col] && row[col] !== filters[col]) {
          return false;
        }
      }
      if (!term) return true;
      return COLUMNS.some((col) => (row[col] ?? '').toLowerCase().includes(term));
    });
  }, [rows, search, filters]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages);
  const pageRows = filtered.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);

  function updateFilter(col: string, value: string) {
    setFilters((prev) => ({ ...prev, [col]: value }));
    setPage(1);
  }

  return (
    <div className="tc-table-wrap">
      <div className="tc-table-controls">
        <input
          type="text"
          placeholder="전체 컬럼 검색…"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(1);
          }}
        />
        {FILTER_COLUMNS.map((col) => (
          <select key={col} value={filters[col] ?? ''} onChange={(e) => updateFilter(col, e.target.value)}>
            <option value="">{col} 전체</option>
            {filterOptions[col].map((v) => (
              <option key={v} value={v}>
                {v}
              </option>
            ))}
          </select>
        ))}
        <span className="tc-table-count">{filtered.length}건</span>
      </div>

      <div className="tc-table-scroll">
        <table>
          <thead>
            <tr>
              {COLUMNS.map((col) => (
                <th key={col}>{col}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {pageRows.map((row, i) => (
              <tr key={i}>
                {COLUMNS.map((col) => (
                  <td key={col}>{row[col]}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="tc-table-pagination">
        <button disabled={currentPage <= 1} onClick={() => setPage(currentPage - 1)}>
          이전
        </button>
        <span>
          {currentPage} / {totalPages}
        </span>
        <button disabled={currentPage >= totalPages} onClick={() => setPage(currentPage + 1)}>
          다음
        </button>
      </div>
    </div>
  );
}
