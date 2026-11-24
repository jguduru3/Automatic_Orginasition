import { createContext, ReactNode, useCallback, useContext, useEffect, useRef, useState } from "react";
import { ApiError } from "../api";

/* ---------- toast ---------- */
const ToastCtx = createContext<(msg: string) => void>(() => {});
export const useToast = () => useContext(ToastCtx);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [msg, setMsg] = useState("");
  const [show, setShow] = useState(false);
  const timer = useRef<number>();
  const toast = useCallback((m: string) => {
    setMsg(m); setShow(true);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setShow(false), 3000);
  }, []);
  return (
    <ToastCtx.Provider value={toast}>
      {children}
      <div className={"toast" + (show ? " show" : "")} role="status" aria-live="polite">{msg}</div>
    </ToastCtx.Provider>
  );
}

/* ---------- data loading ---------- */
export function useApi<T>(load: (signal: AbortSignal) => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const ac = new AbortController();
    setError(null);
    load(ac.signal).then(d => { if (!ac.signal.aborted) setData(d); })
      .catch(e => { if (!ac.signal.aborted && (e as Error).name !== "AbortError") setError((e as Error).message); });
    return () => ac.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick, ...deps]);
  const reload = useCallback(() => setTick(t => t + 1), []);
  return { data, error, reload };
}

/** Runs an action, prevents double submit, and reports errors as a toast. */
export function useAction() {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const run = useCallback(async <T,>(fn: () => Promise<T>, ok?: string | ((r: T) => string)): Promise<T | undefined> => {
    if (busy) return undefined;
    setBusy(true);
    try {
      const r = await fn();
      if (ok) toast(typeof ok === "function" ? ok(r) : ok);
      return r;
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "Something went wrong");
      throw e;
    } finally {
      setBusy(false);
    }
  }, [busy, toast]);
  return { busy, run };
}

export const Loading = ({ error, onRetry }: { error?: string | null; onRetry?: () => void }) =>
  error ? (
    <div className="banner" role="alert">{error} {onRetry && <button className="btn sm" onClick={onRetry}>Retry</button>}</div>
  ) : <div className="skeleton">Loading…</div>;

/* ---------- small pieces ---------- */
export type Tone = "good" | "warn" | "bad" | "info" | "mute";
export const Pill = ({ tone, children }: { tone: Tone; children: ReactNode }) =>
  <span className={`pill p-${tone}`}>{children}</span>;

const STATUS_TONE: Record<string, Tone> = { CONFIRMED: "info", PACKED: "warn", DISPATCHED: "warn", DELIVERED: "good", CANCELLED: "mute" };
const STATUS_LABEL: Record<string, string> = { CONFIRMED: "Confirmed", PACKED: "Packed", DISPATCHED: "Out for delivery", DELIVERED: "Delivered", CANCELLED: "Cancelled" };
export const StatusPill = ({ status }: { status: string }) =>
  <Pill tone={STATUS_TONE[status] ?? "mute"}>{STATUS_LABEL[status] ?? status}</Pill>;

export const Kpi = ({ label, value, detail }: { label: string; value: ReactNode; detail?: ReactNode }) => (
  <div className="card kpi"><div className="lbl">{label}</div><div className="val">{value}</div><div className="d">{detail}</div></div>
);

export const Meter = ({ ratio }: { ratio: number }) => (
  <div className="meter" role="meter" aria-valuenow={Math.round(ratio * 100)} aria-valuemin={0} aria-valuemax={100}>
    <i className={ratio > 1 ? "b" : ratio > 0.85 ? "w" : ""} style={{ width: Math.min(100, Math.max(0, ratio * 100)) + "%" }} />
  </div>
);

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [onClose]);
  return (
    <div className="modal-bg show" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}

export const PageTop = ({ title, sub, children }: { title: string; sub?: string; children?: ReactNode }) => (
  <div className="top"><div><h1>{title}</h1>{sub && <div className="sub">{sub}</div>}</div>{children}</div>
);
