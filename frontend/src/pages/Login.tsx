import { FormEvent, useEffect, useState } from "react";
import { api, ApiError, setToken, User } from "../api";

export default function Login({ onLogin }: { onLogin: (u: User) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [signupOpen, setSignupOpen] = useState(false);
  const [mode, setMode] = useState<"login" | "signup">("login");
  useEffect(() => { api<{ enabled: boolean }>("/api/signup").then(r => setSignupOpen(r.enabled)).catch(() => {}); }, []);

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

  if (mode === "signup") return <Signup onLogin={onLogin} onBack={() => setMode("login")} />;
  return (
    <div className="login">
      <form className="card" onSubmit={submit} aria-label="Sign in">
        <div className="brand" style={{ padding: 0 }}><div className="logo" aria-hidden="true">D</div><div><b>DistributorOS</b><small>Sign in to your business</small></div></div>
        <label htmlFor="email">Email<input id="email" type="email" autoComplete="username" required value={email} onChange={e => setEmail(e.target.value)} /></label>
        <label htmlFor="password">Password<input id="password" type="password" autoComplete="current-password" required value={password} onChange={e => setPassword(e.target.value)} /></label>
        {err && <div className="err" role="alert">{err}</div>}
        <button className="btn pri" type="submit" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        {signupOpen && <button type="button" className="btn ghost" onClick={() => setMode("signup")}>New business? Create an account</button>}
      </form>
    </div>
  );
}

function Signup({ onLogin, onBack }: { onLogin: (u: User) => void; onBack: () => void }) {
  const [f, setF] = useState({ businessName: "", gstin: "", ownerName: "", email: "", password: "" });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true); setErr("");
    try {
      const r = await api<{ token: string; user: User }>("/api/signup", { method: "POST", body: { ...f, gstin: f.gstin.trim().toUpperCase(), email: f.email.trim() } });
      setToken(r.token);
      onLogin(r.user);
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : "Could not create the account");
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login">
      <form className="card" onSubmit={submit} aria-label="Create account">
        <div className="brand" style={{ padding: 0 }}><div className="logo" aria-hidden="true">D</div><div><b>DistributorOS</b><small>Create your business account</small></div></div>
        <label htmlFor="su-b">Business name<input id="su-b" required maxLength={200} value={f.businessName} onChange={set("businessName")} /></label>
        <label htmlFor="su-g">GSTIN (optional)<input id="su-g" maxLength={15} value={f.gstin} onChange={set("gstin")} /></label>
        <label htmlFor="su-n">Your name<input id="su-n" required maxLength={100} value={f.ownerName} onChange={set("ownerName")} /></label>
        <label htmlFor="su-e">Email<input id="su-e" type="email" required autoComplete="username" value={f.email} onChange={set("email")} /></label>
        <label htmlFor="su-p">Password (8+ characters)<input id="su-p" type="password" minLength={8} required autoComplete="new-password" value={f.password} onChange={set("password")} /></label>
        {err && <div className="err" role="alert">{err}</div>}
        <button className="btn pri" type="submit" disabled={busy}>{busy ? "Creating…" : "Create account"}</button>
        <button type="button" className="btn ghost" onClick={onBack}>I already have an account</button>
      </form>
    </div>
  );
}
