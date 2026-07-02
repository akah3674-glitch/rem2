// mail.tm API utility
  const BASE = 'https://api.mail.tm';

  export interface MailMessage {
    id: string;
    subject: string;
    from: { address: string; name: string };
    intro: string;
    createdAt: string;
    seen: boolean;
  }

  export interface MailDetail extends MailMessage {
    html: string[];
    text: string;
  }

  export async function getDomains(): Promise<string[]> {
    const res = await fetch(`${BASE}/domains?page=1`);
    const j = await res.json();
    return (j['hydra:member'] || []).map((d: any) => d.domain);
  }

  export async function createAccount(email: string, password: string): Promise<boolean> {
    const res = await fetch(`${BASE}/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ address: email, password }),
    });
    return res.status === 201;
  }

  export async function getToken(email: string, password: string): Promise<string | null> {
    const res = await fetch(`${BASE}/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ address: email, password }),
    });
    if (!res.ok) return null;
    const j = await res.json();
    return j.token || null;
  }

  export async function getMessages(token: string): Promise<MailMessage[]> {
    const res = await fetch(`${BASE}/messages?page=1`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) return [];
    const j = await res.json();
    return j['hydra:member'] || [];
  }

  export async function getMessage(token: string, id: string): Promise<MailDetail | null> {
    const res = await fetch(`${BASE}/messages/${id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) return null;
    return res.json();
  }

  /** Extract Replit verify link from email html/text */
  export function extractVerifyLink(msg: MailDetail): string | null {
    const combined = (msg.html?.join('') || '') + (msg.text || '');
    const match = combined.match(/https:\/\/replit\.com\/[^"'\s<>]+confirm[^"'\s<>]*/i)
      || combined.match(/https:\/\/replit\.com\/[^"'\s<>]+verif[^"'\s<>]*/i)
      || combined.match(/https:\/\/[^"'\s<>]*replit[^"'\s<>]*token[^"'\s<>]*/i);
    return match ? match[0] : null;
  }

  /** Random username 8-14 chars */
  export function randUsername(): string {
    const adj = ['cool','fast','dark','blue','wild','swift','calm','bright'];
    const noun = ['fox','hawk','wolf','bear','lion','ace','star','byte'];
    const n = Math.floor(Math.random() * 9000) + 1000;
    return adj[Math.floor(Math.random()*adj.length)] +
           noun[Math.floor(Math.random()*noun.length)] + n;
  }

  /** Random password */
  export function randPassword(): string {
    const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP0123456789!@#';
    return Array.from({length: 12}, () => chars[Math.floor(Math.random()*chars.length)]).join('');
  }