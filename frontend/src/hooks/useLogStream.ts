import { useEffect, useRef, useState } from 'react';

/** /api/logs SSE를 구독한다. EventSource가 Last-Event-ID를 자동으로 보내므로 재연결 시 이어보기가 된다. */
export function useLogStream(active: boolean) {
  const [lines, setLines] = useState<string[]>([]);
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!active) {
      return;
    }
    const source = new EventSource('/api/logs');
    sourceRef.current = source;
    source.onmessage = (event) => {
      setLines((prev) => [...prev, event.data]);
    };
    source.onerror = () => {
      // EventSource가 자체적으로 재연결을 시도한다 (Last-Event-ID 포함).
    };
    return () => {
      source.close();
      sourceRef.current = null;
    };
  }, [active]);

  function clear() {
    setLines([]);
  }

  return { lines, clear };
}
