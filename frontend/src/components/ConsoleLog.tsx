import { useEffect, useRef } from 'react';

interface Props {
  lines: string[];
}

export function ConsoleLog({ lines }: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const stickToBottomRef = useRef(true);

  useEffect(() => {
    const el = containerRef.current;
    if (el && stickToBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [lines]);

  function handleScroll() {
    const el = containerRef.current;
    if (!el) return;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    stickToBottomRef.current = distanceFromBottom < 40;
  }

  return (
    <section className="panel">
      <h2>실시간 로그</h2>
      <div className="console-log" ref={containerRef} onScroll={handleScroll}>
        {lines.length === 0 ? (
          <div className="console-empty">로그가 아직 없다.</div>
        ) : (
          lines.map((line, i) => (
            <div className="console-line" key={i}>
              {line}
            </div>
          ))
        )}
      </div>
    </section>
  );
}
