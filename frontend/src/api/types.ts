export type PipelineStatus = 'IDLE' | 'RUNNING' | 'NEEDS_HUMAN' | 'COMPLETED' | 'STOPPED';
export type ItemStatus = 'pending' | 'running' | 'done' | 'blocked';

export interface ProgressItem {
  text: string;
  status: ItemStatus;
}

export interface Progress {
  phase0: ProgressItem[];
  phase1: ProgressItem[];
  phase2: ProgressItem[];
}

export interface StatusResponse {
  status: PipelineStatus;
  message: string;
  progress: Progress | null;
}

export type TcRow = Record<string, string>;

export interface OutputsResponse {
  csv: TcRow[] | null;
  markdown: string | null;
  coverage: string | null;
  csv_filename: string | null;
  markdown_filename: string | null;
}

export interface ApiMessage {
  status: string;
  message: string;
}

export interface ApiError {
  error: string;
}
