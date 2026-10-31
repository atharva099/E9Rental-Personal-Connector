import React, { useEffect, useRef, useState } from 'react';
import { request, validAnswer, validSyncResult, validApproval, validViewSelection, validGmailDetail, validCapabilities, ApiError } from './api.js';
import { validateGmailSearchFilters, validGmailSearchResponse } from './gmailSearch.js';
const SOURCES = [
  { type: 'GMAIL', name: 'Gmail', mark: 'G', tone: 'mail', detail: 'Email content & attachment metadata', label: 'Read-only adapter' },
  { type: 'DRIVE', name: 'Google Drive', mark: 'D', tone: 'drive', detail: 'Extracted documents & file references', label: 'Read-only adapter' },
  { type: 'WHATSAPP', name: 'WhatsApp', mark: 'W', tone: 'whatsapp', detail: 'Accepted messages from selected groups', label: 'Simulated only' },
];
const sourceName = type => SOURCES.find(s => s.type === type)?.name || 'Unknown source';
function Mode({ mode, type, transient = false }) {
  return <span className={'badge ' + (type === 'WHATSAPP' || mode === 'SIMULATED' ? 'amber' : 'muted')}>
    {type === 'WHATSAPP' ? 'SIMULATED · no live WhatsApp' : mode === 'SYNTHETIC' ? 'SYNTHETIC · practice data' : mode === 'LIVE' ? (transient ? 'LIVE · retrieved for this question' : 'LIVE · stored snapshot') : 'Data mode unreported'}
  </span>;
}
function newClientSession() {
  if (typeof window === 'undefined' || !window.crypto) return null;
  if (window.crypto.randomUUID) return window.crypto.randomUUID();
  if (!window.crypto.getRandomValues) return null;
  const bytes = window.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40; bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
function formatReceivedAt(value) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata' }).format(date) + ' IST';
}
export default function App() {
  const [health, setHealth] = useState('Checking');
  const [draft, setDraft] = useState(''); const [token, setToken] = useState('');
  const [backendMode, setBackendMode] = useState(null);
  const [connecting, setConnecting] = useState(false); const [connectionError, setConnectionError] = useState('');
  const [syncing, setSyncing] = useState(false); const [syncResult, setSyncResult] = useState(null); const [syncError, setSyncError] = useState('');
  const [question, setQuestion] = useState(''); const [answer, setAnswer] = useState(null);
  const [searchGmailNow, setSearchGmailNow] = useState(false); const [answerSearchMode, setAnswerSearchMode] = useState('LOCAL');
  const [asking, setAsking] = useState(false); const [askError, setAskError] = useState('');
  const [searchKeyword, setSearchKeyword] = useState(''); const [searchSender, setSearchSender] = useState('');
  const [searchStartDate, setSearchStartDate] = useState(''); const [searchEndDate, setSearchEndDate] = useState('');
  const [gmailSearchResults, setGmailSearchResults] = useState(null); const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState('');
  const [gmailDetail, setGmailDetail] = useState(null); const [viewingSelectionId, setViewingSelectionId] = useState('');
  const [viewError, setViewError] = useState(''); const [viewErrorSelectionId, setViewErrorSelectionId] = useState('');
  const [spentSelections, setSpentSelections] = useState(() => new Set());
  const generation = useRef(0); const controllers = useRef(new Set()); const askInFlight = useRef(false); const searchInFlight = useRef(false);
  const clientSession = useRef(null); if (clientSession.current === null) clientSession.current = newClientSession();
  const viewController = useRef(null); const viewInFlight = useRef(false);
  const viewCloseButton = useRef(null); const viewTrigger = useRef(null); const viewReturnTarget = useRef(null);
  useEffect(() => {
    const controller = new AbortController();
    request('/health', '', { signal: controller.signal }).then(v => setHealth(v.status === 'UP' ? 'Online' : 'Unavailable'))
      .catch(() => { if (!controller.signal.aborted) setHealth('Unavailable'); });
    return () => { controller.abort(); controllers.current.forEach(c => c.abort()); };
  }, []);
  function clearViewer({ restoreFocus = false } = {}) {
    viewController.current?.abort(); viewController.current = null;
    setGmailDetail(null); setViewingSelectionId(''); setViewError(''); setViewErrorSelectionId('');
    if (restoreFocus) viewReturnTarget.current?.focus();
    viewTrigger.current = null; viewReturnTarget.current = null;
  }
  function clearSession(message = '') {
    generation.current++; controllers.current.forEach(c => c.abort()); controllers.current.clear(); askInFlight.current = false; searchInFlight.current = false;
    clearViewer(); viewInFlight.current = false; clientSession.current = newClientSession();
    setToken(''); setBackendMode(null); setDraft(''); setQuestion(''); setAnswer(null); setSpentSelections(new Set()); setSearchGmailNow(false); setAnswerSearchMode('LOCAL');
    setGmailSearchResults(null); setSearchKeyword(''); setSearchSender(''); setSearchStartDate(''); setSearchEndDate('');
    setAsking(false); setSearching(false); setSyncing(false); setSyncResult(null); setSyncError(''); setConnecting(false); setAskError(''); setSearchError(''); setConnectionError(message);
  }
  async function run(operation, onSuccess, onError, onDone, onController, { preserveProviderAuthFailure = false } = {}) {
    const id = generation.current; const controller = new AbortController(); controllers.current.add(controller);
    onController?.(controller);
    try { const value = await operation(controller.signal); if (id === generation.current && !controller.signal.aborted) onSuccess(value); }
    catch(error) {
      if (id !== generation.current || controller.signal.aborted) return;
      if (error.status === 401 && !(preserveProviderAuthFailure && error.trustedProviderAuthFailure === true)) clearSession(error.message);
      else onError(error instanceof ApiError ? error.message : 'The response could not be displayed safely. Please try again.');
    } finally { controllers.current.delete(controller); if (id === generation.current) onDone(); }
  }
  function connect(event) {
    event.preventDefault(); const candidate = draft.trim();
    if (candidate.length < 32) { setConnectionError('Enter a local access token of at least 32 characters.'); return; }
    setConnecting(true); setConnectionError('');
    run(signal => request('/api/capabilities', candidate, { signal }), value => {
      if (!validCapabilities(value)) throw new ApiError(502, 'The backend mode could not be verified safely.');
      setToken(candidate); setBackendMode(value.mode); setDraft(''); setHealth('Online');
    }, setConnectionError, () => setConnecting(false));
  }
  function ask(event) {
    event.preventDefault(); if (!token || !backendMode || !question.trim() || asking || syncing || searching || askInFlight.current || searchInFlight.current) return;
    clearViewer();
    const personalMode = backendMode === 'PERSONAL';
    const liveRequest = personalMode || searchGmailNow;
    if (liveRequest && !window.confirm(personalMode
      ? 'Ask the connected Gmail account now? This makes one bounded, read-only retrieval for this question. It does not sync, change messages, retry automatically, or authorize future requests.'
      : 'Send this one question to connected Gmail now? This performs one bounded, read-only search. It does not sync or change messages, retry automatically, or authorize future requests.')) return;
    askInFlight.current = true;
    setGmailSearchResults(null); setSearchError(''); setAsking(true); setAskError(''); setAnswer(null); setSpentSelections(new Set());
    const body = { question: question.trim(), ...(liveRequest ? { searchGmailNow: true } : {}) };
    run(signal => request('/api/qa/ask', token, { method: 'POST', body, signal, timeoutMs: liveRequest ? 240000 : 15000,
      clientSessionId: clientSession.current || undefined }), value => {
      if (!validAnswer(value)) throw new ApiError(502, 'Unexpected answer format. No answer was displayed.');
      setAnswer(value); setAnswerSearchMode(liveRequest ? 'GMAIL_REQUEST' : 'LOCAL');
    }, setAskError, () => { askInFlight.current = false; setAsking(false); }, undefined, { preserveProviderAuthFailure: true });
  }
  function updateGmailFilter(setter, value) {
    setter(value);
    if (gmailSearchResults || gmailDetail || viewingSelectionId) {
      setGmailSearchResults(null); clearViewer(); setSpentSelections(new Set());
    }
  }
  function search(event) {
    event.preventDefault(); if (!token || !backendMode || !clientSession.current || searching || syncing || asking || searchInFlight.current) return;
    const validated = validateGmailSearchFilters({ keyword: searchKeyword, sender: searchSender,
      startDate: searchStartDate, endDate: searchEndDate });
    if (!validated.filters) { setSearchError(validated.error); return; }
    if (!window.confirm('Search the connected Gmail account now? This makes one bounded, read-only search (up to 20 messages, one page). It does not sync or change messages, retry automatically, or authorize future searches.')) return;
    searchInFlight.current = true;
    clearViewer(); setAnswer(null); setAskError(''); setGmailSearchResults(null); setSearchError(''); setSpentSelections(new Set());
    const filters = validated.filters;
    const activeToken = token; const sessionId = clientSession.current;
    setSearching(true);
    run(async signal => {
      const approved = await request('/api/gmail/search/approval', activeToken, { method: 'POST', body: filters,
        signal, clientSessionId: sessionId, timeoutMs: 7000 });
      if (!validApproval(approved)) throw new ApiError(502, 'Gmail search approval could not be verified safely.');
      let approvalToken = approved.approval_token;
      try {
        const result = await request('/api/gmail/search', activeToken, { method: 'POST', body: { approval_token: approvalToken },
          signal, clientSessionId: sessionId, timeoutMs: 190000 });
        if (!validGmailSearchResponse(result)) throw new ApiError(502, 'Gmail search returned an unsupported result. No results were displayed.');
        return result;
      } finally { approvalToken = null; }
    }, value => setGmailSearchResults(value), setSearchError, () => {
      searchInFlight.current = false; setSearching(false);
    });
  }
  function viewFullEmail(event, evidence) {
    const selectionId = evidence?.view_selection_id || evidence?.selection_id;
    if (!token || !backendMode || !clientSession.current || !validViewSelection(selectionId) || viewInFlight.current || !window.confirm(
      'Open this full email? E9Rental will make one additional live detail request for this selected message. The backend verifies the approved Gmail account and makes a profile check plus one message read. The message is not saved locally; cancel to make no request.'
    )) return;
    viewInFlight.current = true; setSpentSelections(previous => new Set(previous).add(selectionId));
    setGmailDetail(null); setViewError(''); setViewErrorSelectionId(selectionId); setViewingSelectionId(selectionId); viewTrigger.current = event.currentTarget;
    viewReturnTarget.current = event.currentTarget.closest('.citation, .gmail-search-result, .recent-gmail-row');
    run(async signal => {
      const approved = await request('/api/gmail/messages/view-approval', token, { method: 'POST', body: { selection_id: selectionId }, signal, clientSessionId: clientSession.current, timeoutMs: 25000 });
      if (!validApproval(approved)) throw new ApiError(502, 'The selected email could not be approved safely.');
      let approvalToken = approved.approval_token;
      try {
        const detail = await request('/api/gmail/messages/view', token, { method: 'POST', body: { approval_token: approvalToken }, signal, clientSessionId: clientSession.current, timeoutMs: 25000 });
        if (!validGmailDetail(detail)) throw new ApiError(502, 'The email detail response was incomplete or unsupported.');
        return detail;
      } finally { approvalToken = null; }
    }, value => setGmailDetail(value), setViewError, () => {
      viewInFlight.current = false; viewController.current = null; setViewingSelectionId('');
    }, controller => { viewController.current = controller; }, { preserveProviderAuthFailure: true });
  }
  function closeFullEmail() { clearViewer({ restoreFocus: true }); }
  useEffect(() => {
    if (!gmailDetail) return undefined;
    viewCloseButton.current?.focus();
    const onKeyDown = event => {
      if (event.key === 'Escape') closeFullEmail();
      if (event.key === 'Tab') { event.preventDefault(); viewCloseButton.current?.focus(); }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [gmailDetail]);
  function syncGmail() {
    if (backendMode !== 'SANDBOX' || !token || syncing || asking || searching || searchInFlight.current) return;
    setSyncing(true); setSyncError(''); setSyncResult(null);
    run(signal => request('/api/gmail/sync', token, { method: 'POST', signal }), value => {
      if (!validSyncResult(value)) throw new ApiError(502, 'Unexpected synchronization response.');
      setSyncResult(value);
    }, setSyncError, () => setSyncing(false));
  }
  function syncSummary(result) {
    if (result.status === 'BASELINE_ESTABLISHED') return 'Sync baseline established. No historical messages were imported.';
    if (result.status === 'NO_CHANGES') return 'No Gmail changes were found since the last sync.';
    if (result.replayed) return 'Sync completed; this history batch was already applied.';
    return `Sync complete: ${result.inserted} added, ${result.updated} updated, ${result.removed} removed.`;
  }
  return <div className="app-shell">
    <aside className="sidebar">
      <a className="brand" href="#workspace"><span className="brand-mark">E9</span><span>E9Rental<small>CONNECTOR PRACTICE</small></span></a>
      <div className="nav-label">WORKSPACE</div>
      <nav aria-label="Workspace navigation"><a className="active" href="#workspace"><span>◫</span> Overview</a>{token && backendMode && <><a href="#ask"><span>↗</span> {backendMode === 'PERSONAL' ? 'Ask Gmail' : 'Ask your sources'}</a><a href="#search"><span>⌕</span> Search Gmail</a></>}</nav>
      <div className="sidebar-note"><span className="small-dot"/> LOCAL PRACTICE<p>Read-only exploration.<br/>Your data stays with your local backend.</p></div>
    </aside>
    <main id="workspace">
      <header className="topbar"><span>Workspace <span className="slash">/</span> Overview</span><span className={'health ' + (health === 'Online' ? 'up' : '')}><span className="small-dot"/>{health === 'Online' ? 'Backend online' : 'Backend ' + health.toLowerCase()}</span></header>
      <section className="intro"><div className="eyebrow">CONNECTED KNOWLEDGE</div><h1>Your sources.<br className="mobile-break"/> One clear starting point.</h1><p>Find the evidence behind your rental business questions.</p><span className="practice-pill">Independent practice project · Local workspace</span></section>
      {token && backendMode === 'SANDBOX' && <section className="sources" aria-label="Data sources">{SOURCES.map(s => <article className="source-card" key={s.type}>
        <div className="source-top"><span className={'source-mark ' + s.tone}>{s.mark}</span><span className={'badge ' + (s.type === 'WHATSAPP' ? 'amber' : 'muted')}>{s.label}</span></div>
        <h2>{s.name}</h2><p>{s.detail}</p><div className="source-foot">{s.type === 'WHATSAPP' ? 'Synthetic events · not connected to Meta' : 'Indexed snapshots · live account status not checked'}</div>
      </article>)}</section>}
      {token && backendMode === 'PERSONAL' && <section className="panel personal-mode-note" aria-label="Live Gmail-only workspace">
        <div className="eyebrow">PERSONAL · LIVE GMAIL ONLY</div>
        <p>Gmail is retrieved directly for each approved question, search, or full-email view. Newly retrieved content is not saved to a permanent local Gmail index. This workspace does not delete previously stored data.</p>
      </section>}
      <section className="connection panel" aria-labelledby="connection-title" aria-busy={syncing}>
        <div><h2 id="connection-title">{token ? (backendMode === 'PERSONAL' ? 'Personal Gmail connected' : 'Local session connected') : 'Connect to your local backend'}</h2><p>{token ? 'Token held in memory for this tab. Disconnect to clear questions and results.' : 'Use your local API access token. It is never saved to browser storage.'}</p></div>
        {token ? <div className="connection-actions">
          {backendMode === 'SANDBOX' && <button className="secondary" onClick={syncGmail} disabled={syncing || asking || searching}>{syncing ? 'Syncing Gmail…' : 'Sync Gmail now'}</button>}
          <button className="secondary" onClick={() => clearSession()} disabled={syncing}>Disconnect</button>
        </div> :
          <><form onSubmit={connect} className="connect-form"><label className="sr-only" htmlFor="access-token">Local access token</label><input id="access-token" type="password" autoComplete="off" spellCheck="false" value={draft} onChange={e => setDraft(e.target.value)} placeholder="Local access token" disabled={connecting}/><button disabled={connecting || !draft}>{connecting ? 'Connecting…' : 'Connect'}</button></form>{connecting && <button className="secondary" type="button" onClick={() => clearSession()} aria-label="Cancel connection">Cancel</button>}</>}
        {token && backendMode === 'SANDBOX' && <p className="full-width">Sync runs only when requested, may contact Gmail, and applies bounded changes to the local index. No background sync runs.</p>}
        {connectionError && <p className="error full-width" role="alert">{connectionError}</p>}
        {syncing && <p className="status full-width" role="status">Running one bounded, user-requested Gmail history sync. No background sync is active.</p>}
        {syncError && <p className="error full-width" role="alert">{syncError}</p>}
        {syncResult && <p className="status full-width" role="status">{syncSummary(syncResult)}</p>}
      </section>
      {token && backendMode && <section id="ask" className="panel question-panel" aria-labelledby="ask-title" aria-busy={asking}>
        <div className="section-heading"><div><div className="eyebrow">{backendMode === 'PERSONAL' ? 'LIVE GMAIL' : 'ASK YOUR SOURCES'}</div><h2 id="ask-title">{backendMode === 'PERSONAL' ? 'Ask Gmail' : 'Start with a question'}</h2></div><span className="badge green">Deterministic · no external AI</span></div>
        <p className="section-description">{backendMode === 'PERSONAL' ? 'Ask about a specific Gmail topic or supported date/recent search. Answers use bounded, freshly retrieved Gmail evidence.' : 'Get exact recorded excerpts with citations. If the evidence is missing, we’ll say so.'}</p>
        <form onSubmit={ask}><label className="sr-only" htmlFor="question">Your question</label><textarea id="question" maxLength={1000} rows={3} value={question} onChange={e => setQuestion(e.target.value)} placeholder={backendMode === 'PERSONAL' ? 'Ask about a Gmail topic, date, or recent messages.' : 'What do Gmail and Drive say about quotation?'} disabled={!token || asking || syncing || searching}/>
          {backendMode === 'SANDBOX' && <label className="live-gmail-option"><input type="checkbox" checked={searchGmailNow} onChange={e => setSearchGmailNow(e.target.checked)} disabled={!token || asking || syncing || searching}/><span><strong>Search Gmail now</strong><small>Explicitly send this question to Gmail. Off means local index only.</small></span></label>}
          {backendMode === 'PERSONAL'
            ? <p className="hint">Each question requires fresh confirmation and performs one bounded, read-only Gmail retrieval. Recent-email questions show structured rows; other supported questions show cited evidence. Results are bounded and may not cover the complete mailbox. The flow does not sync or automatically retry, and does not generate AI summaries.</p>
            : <p className="hint">Direct Gmail search supports a topic; “Find Gmail emails from recruiter@example.com about interview between 2026-10-01 and 2026-10-18”; “Show my Gmail emails from yesterday”; “Show my Gmail emails from today”; or “Show my latest 10 Gmail emails.” Relative dates use Asia/Kolkata. Spam and Trash are excluded; at most 20 messages are retrieved (10 for latest-email requests). Each live question asks for confirmation and runs once without automatic retry. Answers are exact cited excerpts, not AI-generated summaries.</p>}
          <div className="question-footer"><span>{backendMode === 'PERSONAL' ? 'Newly retrieved Gmail content is used for this approved request and is not saved to a permanent local Gmail index.' : 'Local mode searches the stored index; direct Gmail results are fetched for this question only.'}</span><button disabled={!token || !question.trim() || asking || syncing || searching} type="submit">{asking ? 'Finding evidence…' : backendMode === 'PERSONAL' ? 'Ask Gmail' : 'Ask question'} <span aria-hidden="true">↗</span></button></div>
        </form>
        <div className="examples"><span>Try a question</span>{(backendMode === 'PERSONAL'
          ? ['Show my latest 10 Gmail emails', 'Show my Gmail emails from today', 'Find Gmail emails about invoice']
          : ['Show quotation records across Gmail and Drive', 'What commitments were made in WhatsApp?']).map(q => <button key={q} className="example" disabled={!token || asking || syncing} onClick={() => setQuestion(q)}>{q}</button>)}</div>
        {asking && <p role="status" className="status">{backendMode === 'PERSONAL' || searchGmailNow ? 'Running one bounded direct Gmail question…' : 'Searching your local index…'}</p>}
        {askError && <p className="error" role="alert">{askError}</p>}
        {!answer && !asking && !askError && <div className="empty"><span className="empty-icon">↗</span><p>Your answer and supporting sources will appear here.</p></div>}
        {answer && <div className="answer" aria-live="polite">
          <h3>{answer.status === 'INSUFFICIENT_EVIDENCE' ? 'Not enough evidence' : answer.recent_emails?.length ? 'Recent Gmail results' : 'Recorded evidence'}</h3>
          <p className="answer-mode" role="status">{answerSearchMode === 'GMAIL_REQUEST' ? 'Gmail-backed request · retrieved for this question only.' : 'Local index only'}</p>
          {answer.gmail_retrieval && <p className="hint" role="status">Direct retrieval: {answer.gmail_retrieval.messages_retrieved} messages · {answer.gmail_retrieval.gmail_rest_attempts} Gmail API requests · {answer.gmail_retrieval.oauth_refresh_attempts} OAuth refresh requests.</p>}
          <p className="answer-text">{answer.answer}</p>
          {answer.limited_coverage && <p className="hint">Coverage is limited. This is not a complete inventory.</p>}
          {answer.partial_results && <p className="hint" role="status">Gmail returned more matches than this bounded lookup could inspect. Additional matching emails may exist; this answer uses only the retrieved evidence. Narrow the topic using up to three specific words.</p>}
          {!!answer.recent_emails?.length && <section className="recent-gmail-results" aria-label="Recent Gmail messages">
            <h3>Recent Gmail messages <span className="count">{answer.recent_emails.length}</span></h3>
            <p className="hint">Newest first · up to 10 messages · not complete mailbox coverage.</p>
            <div className="recent-gmail-list">{answer.recent_emails.map((row,index) => <article className="recent-gmail-row" key={row.selection_id || `${row.received_at || 'unknown'}-${index}`}>
              <h4>{row.subject || 'Subject unavailable'}</h4>
              <dl className="gmail-result-meta"><dt>From</dt><dd>{row.sender || 'Sender unavailable'}</dd><dt>Received</dt><dd>{formatReceivedAt(row.received_at) || 'Date unavailable'}</dd></dl>
              <p className="gmail-result-excerpt">{row.excerpt || 'No readable preview is available.'}</p>
              {clientSession.current && validViewSelection(row.selection_id) && !spentSelections.has(row.selection_id) &&
                <button className="secondary" type="button" disabled={Boolean(viewingSelectionId) || viewInFlight.current}
                  onClick={event => viewFullEmail(event,row)}>{viewingSelectionId === row.selection_id ? 'Opening email…' : 'View full email'}</button>}
              {viewingSelectionId === row.selection_id && <p className="status" role="status">Opening one selected Gmail message…</p>}
              {viewError && viewErrorSelectionId === row.selection_id && <p className="error" role="alert">{viewError}</p>}
            </article>)}</div>
          </section>}
          {!!answer.evidence.length && <><h3>Supporting citations <span className="count">{answer.evidence.length}</span></h3><div className="citations">{answer.evidence.map(e =>
            <article className="citation" key={e.reference} tabIndex={-1}><div className="citation-heading"><strong>{e.reference} · {sourceName(e.source_type)}</strong><Mode mode={e.data_mode} type={e.source_type} transient={Boolean(answer.gmail_retrieval)}/></div>
              {e.title ? <h4>{e.title}</h4> : answer.gmail_retrieval && e.source_type === 'GMAIL' ? <h4>Gmail email</h4> : null}<blockquote>{e.excerpt}</blockquote>
              {answer.gmail_retrieval && e.source_type === 'GMAIL' && e.data_mode === 'LIVE' && clientSession.current && validViewSelection(e.view_selection_id) && !spentSelections.has(e.view_selection_id) &&
                <button className="secondary" type="button" disabled={Boolean(viewingSelectionId) || viewInFlight.current}
                  onClick={event => viewFullEmail(event, e)}>{viewingSelectionId === e.view_selection_id ? 'Opening email…' : 'View full email'}</button>}
              {viewingSelectionId === e.view_selection_id && <p className="status" role="status">Opening one selected Gmail message…</p>}
              {viewError && viewErrorSelectionId === e.view_selection_id && <p className="error" role="alert">{viewError}</p>}
            </article>)}</div></>}
          <details className="limitations"><summary>What this answer can tell you</summary><ul>{answer.limitations.map(l => <li key={l}>{l}</li>)}</ul></details>
        </div>}
      </section>}
      {token && backendMode && <section id="search" className="panel search-panel" aria-labelledby="search-title" aria-busy={searching}>
        <div className="section-heading"><div><div className="eyebrow">LIVE GMAIL SEARCH</div><h2 id="search-title">Search Gmail</h2></div><span className="hint">One page · up to 20 messages</span></div>
        <p className="section-description">Search the connected Gmail account directly. Enter at least one filter: up to three alphanumeric keyword terms, an exact sender address, and/or a date range. Supply both dates together; date boundaries are inclusive in Asia/Kolkata and ranges are limited to 366 days. Spam and Trash are excluded.</p>
        <form className="gmail-search-form" onSubmit={search} noValidate>
          <label htmlFor="gmail-search-keyword">Keyword<input id="gmail-search-keyword" aria-label="Keyword" value={searchKeyword} onChange={e => { setSearchError(''); updateGmailFilter(setSearchKeyword,e.target.value); }} maxLength={128} autoComplete="off" placeholder="e.g. interview salary" disabled={!token || searching || syncing || asking}/><small>Up to three words; letters and numbers only.</small></label>
          <label htmlFor="gmail-search-sender">Exact sender email (optional)<input id="gmail-search-sender" aria-label="Exact sender email" type="email" value={searchSender} onChange={e => { setSearchError(''); updateGmailFilter(setSearchSender,e.target.value); }} maxLength={254} autoComplete="off" placeholder="name@example.com" disabled={!token || searching || syncing || asking}/></label>
          <label htmlFor="gmail-search-start">From date (optional)<input id="gmail-search-start" aria-label="From date" type="date" value={searchStartDate} onChange={e => { setSearchError(''); updateGmailFilter(setSearchStartDate,e.target.value); }} max="9999-12-30" disabled={!token || searching || syncing || asking}/></label>
          <label htmlFor="gmail-search-end">To date (optional)<input id="gmail-search-end" aria-label="To date" type="date" value={searchEndDate} onChange={e => { setSearchError(''); updateGmailFilter(setSearchEndDate,e.target.value); }} max="9999-12-30" disabled={!token || searching || syncing || asking}/></label>
          <button type="submit" disabled={!token || searching || syncing || asking}>{searching ? 'Searching Gmail…' : 'Search Gmail'}</button>
        </form>
        {!token && <p className="hint">Connect to your local backend above to search Gmail.</p>}
        {searchError && <p className="error" role="alert">{searchError}</p>}
        {searching && <p role="status" className="status">Requesting fresh approval, then running one bounded Gmail search. No automatic retry will occur.</p>}
        {gmailSearchResults?.partial_results && <p className="gmail-coverage-warning" role="status">{gmailSearchResults.coverage_warning || 'More matching emails may exist beyond this one-page result.'} This is not a complete mailbox inventory.</p>}
        {gmailSearchResults === null && !searching && !searchError && token && <p className="hint">Results appear here only after you confirm each live search. Search results remain in this tab's memory and are not saved by the dashboard.</p>}
        {gmailSearchResults && gmailSearchResults.results.length === 0 && <p className="empty" role="status">No Gmail messages matched these filters.</p>}
        {!!gmailSearchResults?.results.length && <div className="gmail-search-results" aria-label="Gmail search results">
          <p className="hint">{gmailSearchResults.results.length} messages shown · one page only</p>
          {gmailSearchResults.results.map(row => <article className="gmail-search-result" key={row.selection_id} tabIndex={-1}>
            <h3>{row.subject || 'Untitled email'}</h3>
            <dl className="gmail-result-meta"><dt>From</dt><dd>{row.sender || 'Sender unavailable'}</dd><dt>Received</dt><dd>{formatReceivedAt(row.received_at) || 'Date unavailable'}</dd></dl>
            <p className="gmail-result-excerpt">{row.excerpt || 'No readable preview is available.'}</p>
            {clientSession.current && validViewSelection(row.selection_id) && !spentSelections.has(row.selection_id) &&
              <button className="secondary" type="button" disabled={Boolean(viewingSelectionId) || viewInFlight.current || searching}
                onClick={event => viewFullEmail(event,row)}>{viewingSelectionId === row.selection_id ? 'Opening email…' : 'View full email'}</button>}
            {viewingSelectionId === row.selection_id && <p className="status" role="status">Opening one selected Gmail message…</p>}
            {viewError && viewErrorSelectionId === row.selection_id && <p className="error" role="alert">{viewError}</p>}
          </article>)}
        </div>}
        {gmailDetail && <div className="viewer-backdrop"><section className="gmail-viewer" role="dialog" aria-modal="true" aria-labelledby="gmail-viewer-title">
          <div className="viewer-heading"><div><div className="eyebrow">GMAIL MESSAGE</div><h3 id="gmail-viewer-title">{gmailDetail.subject || 'Untitled email'}</h3></div>
            <button ref={viewCloseButton} className="secondary" type="button" onClick={closeFullEmail}>Close</button></div>
          <dl className="viewer-meta">
            {gmailDetail.sender && <><dt>From</dt><dd>{gmailDetail.sender}</dd></>}
            {gmailDetail.recipient && <><dt>To</dt><dd>{gmailDetail.recipient}</dd></>}
            {formatReceivedAt(gmailDetail.received_at) && <><dt>Received</dt><dd>{formatReceivedAt(gmailDetail.received_at)}</dd></>}
          </dl>
          {(gmailDetail.incomplete || gmailDetail.body_truncated || !gmailDetail.body_available || gmailDetail.attachment_names_truncated) &&
            <p className="hint" role="status">Some email details are unavailable or incomplete. The message was not fully represented.</p>}
          {!gmailDetail.body_available && <p className="status" role="status">No readable message body was available.</p>}
          {gmailDetail.body_available && <pre className="gmail-body">{gmailDetail.body_text}</pre>}
          {!!gmailDetail.attachment_names.length && <><h4>Attachments</h4><ul className="attachment-list">{gmailDetail.attachment_names.map((name,index)=><li key={`${index}-${name}`}>{name}</li>)}</ul></>}
          <p className="hint">Message details are shown temporarily and are not saved by this dashboard.</p>
        </section></div>}
      </section>}
      <footer>Evidence first. Always traceable.<span>E9Rental Connector Practice · Local, single-user interface</span></footer>
    </main>
  </div>;
}
