import { FormEvent, useState } from "react";
import { api, post } from "../api";
import { Loading, Modal, PageTop, Pill, useAction, useApi } from "../components/ui";
import { lakh, titleCase } from "../format";
import { useUser } from "../App";

interface Member { id: number; email: string; name: string; role: string; active: boolean; monthlyTarget: number; beat?: string }

export default function Team() {
  const me = useUser();
  const list = useApi(s => api<Member[]>("/api/users", { signal: s }));
  const [adding, setAdding] = useState(false);
  const { busy, run } = useAction();
  if (!list.data) return <Loading error={list.error} onRetry={list.reload} />;
  return (
    <>
      <PageTop title="Team" sub="Who can sign in, and what each role can do."><button className="btn pri sm" onClick={() => setAdding(true)}>Add person</button></PageTop>
      <div className="card"><div className="tbl-wrap"><table>
        <thead><tr><th>Name</th><th>Role</th><th>Beat</th><th className="r">Monthly target</th><th>Status</th><th /></tr></thead>
        <tbody>{list.data.map(m => (
          <tr key={m.id}><td>{m.name}<span className="sub">{m.email}</span></td><td>{titleCase(m.role)}</td><td>{m.beat ?? "–"}</td>
            <td className="r num">{m.role === "SALES" ? lakh(m.monthlyTarget) : "–"}</td>
            <td>{m.active ? <Pill tone="good">Active</Pill> : <Pill tone="mute">Removed</Pill>}</td>
            <td>{m.active && m.id !== me.id && <button className="btn ghost sm" disabled={busy}
              onClick={() => run(() => post(`/api/users/${m.id}/deactivate`), `${m.name} can no longer sign in`).then(list.reload).catch(() => {})}>Remove access</button>}</td></tr>
        ))}</tbody>
      </table></div></div>
      {adding && <AddMember onClose={() => setAdding(false)} onDone={() => { setAdding(false); list.reload(); }} />}
    </>
  );
}

function AddMember({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ name: "", email: "", password: "", role: "SALES", monthlyTarget: "0", beat: "" });
  const { busy, run } = useAction();
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  async function submit(e: FormEvent) {
    e.preventDefault();
    await run(() => post("/api/users", { ...f, monthlyTarget: Number(f.monthlyTarget) || 0, beat: f.beat || undefined }), "Person added").then(onDone).catch(() => {});
  }
  return (
    <Modal title="Add person" onClose={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 10 }}>
        <label className="field" htmlFor="m-n">Name<input id="m-n" required maxLength={100} value={f.name} onChange={set("name")} /></label>
        <label className="field" htmlFor="m-e">Email<input id="m-e" type="email" required value={f.email} onChange={set("email")} /></label>
        <label className="field" htmlFor="m-p">Temporary password (8+ characters)<input id="m-p" type="password" minLength={8} required value={f.password} onChange={set("password")} autoComplete="new-password" /></label>
        <label className="field" htmlFor="m-r">Role<select id="m-r" value={f.role} onChange={set("role")}>
          <option value="SALES">Salesperson</option><option value="DELIVERY">Delivery</option><option value="ACCOUNTANT">Accountant</option><option value="OWNER">Owner</option></select></label>
        {f.role === "SALES" && <>
          <label className="field" htmlFor="m-t">Monthly target (₹)<input id="m-t" type="number" min={0} value={f.monthlyTarget} onChange={set("monthlyTarget")} /></label>
          <label className="field" htmlFor="m-b">Beat / area<input id="m-b" maxLength={200} value={f.beat} onChange={set("beat")} /></label></>}
        <div className="actions" style={{ margin: 0 }}><button className="btn pri" disabled={busy}>Add person</button><button type="button" className="btn" onClick={onClose}>Cancel</button></div>
      </form>
    </Modal>
  );
}
