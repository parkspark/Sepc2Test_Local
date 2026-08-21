import type { ApiError, ApiMessage, OutputsResponse, StatusResponse } from './types';

async function parseOrThrow<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const message = (body as ApiError | null)?.error ?? `요청 실패 (HTTP ${res.status})`;
    throw new Error(message);
  }
  return body as T;
}

export async function fetchStatus(): Promise<StatusResponse> {
  const res = await fetch('/api/status');
  return parseOrThrow<StatusResponse>(res);
}

export async function uploadFiles(pdf: File, csv: File | null): Promise<ApiMessage> {
  const form = new FormData();
  form.append('pdf', pdf);
  if (csv) {
    form.append('csv', csv);
  }
  const res = await fetch('/api/upload', { method: 'POST', body: form });
  return parseOrThrow<ApiMessage>(res);
}

export async function stopPipeline(): Promise<ApiMessage> {
  const res = await fetch('/api/stop', { method: 'POST' });
  return parseOrThrow<ApiMessage>(res);
}

export async function resumePipeline(): Promise<ApiMessage> {
  const res = await fetch('/api/resume', { method: 'POST' });
  return parseOrThrow<ApiMessage>(res);
}

export async function fetchOutputs(): Promise<OutputsResponse> {
  const res = await fetch('/api/outputs');
  return parseOrThrow<OutputsResponse>(res);
}

export function downloadUrl(fileType: 'csv' | 'markdown'): string {
  return `/api/download/${fileType}`;
}
