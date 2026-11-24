import { FormEvent, useState } from "react";
import { api, ApiError, setToken, User } from "../api";

export default function Login({ onLogin }: { onLogin: (u: User) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true); setErr("");
    try {
      const r = await api<{ token: string; user: User }>("/api/auth/login", { method: "POST", body: { email: email.trim(), password } });
      setToken(r.token);
      onLogin(r.user);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "Sign-in failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <form className="card" onSubmit={submit} aria-label="Sign in">
        <div className="brand" style={{ padding: 0 }}><div className="logo" aria-hidden="true">D</div><div><b>DistributorOS</b><small>Sign in to your business</small></div></div>
        <label htmlFor="email">Email<input id="email" type="email" autoComplete="username" required value={email} onChange={e => setEmail(e.target.value)} /></label>
        <label htmlFor="password">Password<input id="password" type="password" autoComplete="current-password" required value={password} onChange={e => setPassword(e.target.value)} /></label>
        {err && <div className="err" role="alert">{err}</div>}
        <button className="btn pri" type="submit" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
      </form>
    </div>
  );
}
