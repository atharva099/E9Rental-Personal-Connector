export class ApiError extends Error {
  constructor(status, message, category = '', trustedProviderAuthFailure = false) {
    super(message); this.status = status; this.category = category; this.trustedProviderAuthFailure = trustedProviderAuthFailure;
  }
}
const safeGmailReasons = new Set(['UNSPECIFIED','RATE_LIMIT','QUOTA_EXCEEDED','INSUFFICIENT_SCOPE']);
const viewerErrorTexts = {
  GMAIL_REQUEST_TIMEOUT: ['The Gmail request timed out before completion. No result was returned.'],
  GMAIL_VIEWER_CAPACITY_EXHAUSTED: ['The Gmail viewer is busy; try again later.'],
  GMAIL_UNEXPECTED_RETRIEVAL_FAILURE: [
    'Gmail message selection is invalid or expired',
    'Gmail message selection capacity is unavailable',
    'Gmail message request failed',
  ],
  GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED: ['Gmail question retrieval exceeded its response-size limit'],
  GMAIL_REQUEST_BUDGET_EXHAUSTED: ['Gmail question retrieval attempt budget exhausted'],
  GMAIL_PROVIDER_AUTH_FAILURE: ['Gmail message retrieval failed'],
  GMAIL_PROVIDER_PERMISSION_FAILURE: ['Gmail message retrieval failed'],
  GMAIL_PROVIDER_RATE_LIMIT: ['Gmail message retrieval failed'],
  GMAIL_PROVIDER_UNAVAILABLE: ['Gmail message retrieval failed'],
  GMAIL_PROVIDER_REQUEST_FAILURE: ['Gmail message retrieval failed'],
  GMAIL_NETWORK_FAILURE: ['Gmail request failed','OAuth refresh failed'],
};
const validErrorText = value => typeof value === 'string' && value.length > 0 && value.length <= 512 &&
  value.trim().length > 0 && !/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(value);
const exactKeys = (payload, expected) => payload && typeof payload === 'object' && !Array.isArray(payload) &&
  Object.keys(payload).length === expected.length && expected.every(key => Object.hasOwn(payload,key));
export function validCapabilities(value) {
  return Boolean(exactKeys(value,['mode']) && ['PERSONAL','SANDBOX'].includes(value.mode));
}
function expectedOuterStatus(upstream) { return [400,401,413,503].includes(upstream) ? upstream : 502; }
function viewerErrorContract(status, payload, category) {
  if (!exactKeys(payload,['error','error_category','upstream_status','upstream_reason']) || !validErrorText(payload.error) ||
      !viewerErrorTexts[category]?.includes(payload.error)) return null;
  const upstream = payload?.upstream_status;
  const reason = payload?.upstream_reason;
  if (!Number.isInteger(upstream) || !safeGmailReasons.has(reason)) return null;
  const matches = (http, provider) => status === http && upstream === provider;
  if (category === 'GMAIL_REQUEST_TIMEOUT' && matches(503,503))
    return { message:'The email viewer timed out before the message could be opened. Run a fresh Gmail search and select the email again.' };
  if (category === 'GMAIL_VIEWER_CAPACITY_EXHAUSTED' && matches(503,503))
    return { message:'The email viewer is busy. Wait briefly, then run a fresh Gmail search and select the email again.' };
  if (category === 'GMAIL_UNEXPECTED_RETRIEVAL_FAILURE' && matches(502,404))
    return { message:'This email selection or approval is no longer valid or has expired. Run a fresh Gmail search and select the email again.' };
  if (category === 'GMAIL_UNEXPECTED_RETRIEVAL_FAILURE' && matches(503,503))
    return { message:'The email viewer is temporarily busy. Wait briefly, then run a fresh Gmail search and select the email again.' };
  if (category === 'GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED' && matches(503,503))
    return { message:'This email exceeds the viewer’s safe response-size limit and could not be opened.' };
  if (category === 'GMAIL_REQUEST_BUDGET_EXHAUSTED' && matches(503,503))
    return { message:'The email viewer reached its safe request limit. Run a fresh Gmail search and select the email again.' };
  if (category === 'GMAIL_PROVIDER_AUTH_FAILURE' && matches(401,401))
    return { message:'Gmail did not accept the current authorization. Reconnect the approved account, then run a fresh search.', trustedProviderAuthFailure:true };
  if (category === 'GMAIL_PROVIDER_PERMISSION_FAILURE' && matches(502,403) &&
      ['QUOTA_EXCEEDED','INSUFFICIENT_SCOPE','UNSPECIFIED'].includes(reason))
    return { message:reason === 'QUOTA_EXCEEDED'
      ? 'Gmail’s request quota is temporarily unavailable. Wait before running a fresh search.'
      : 'Gmail did not authorize this message read. Check the connected account’s access, then run a fresh search.' };
  if (category === 'GMAIL_PROVIDER_RATE_LIMIT' && status === 502 &&
      ((upstream === 403 && reason === 'RATE_LIMIT') || (upstream === 429 && safeGmailReasons.has(reason))))
    return { message:'Gmail is temporarily limiting message reads. Wait briefly, then run a fresh search.' };
  if (category === 'GMAIL_PROVIDER_UNAVAILABLE' && matches(503,503))
    return { message:'Gmail is temporarily unavailable. Check the connection, then run a fresh search.' };
  if (category === 'GMAIL_NETWORK_FAILURE' && matches(503,503) && reason === 'UNSPECIFIED')
    return { message:'Gmail is temporarily unavailable. Check the connection, then run a fresh search.' };
  if (category === 'GMAIL_PROVIDER_REQUEST_FAILURE' && matches(502,502))
    return { message:'Gmail could not complete this message read. Run a fresh search and select the email again.' };
  return null;
}
function viewerStatusOnlyContract(status,payload) {
  if (!validErrorText(payload?.error) || !payload || typeof payload !== 'object' || Array.isArray(payload) ||
      Object.hasOwn(payload,'error_category')) return false;
  if (exactKeys(payload,['error'])) {
    const bareErrors = {
      400: ['Invalid request or configuration'],
      401: ['Local bearer token required'],
      403: ['Sandbox access denied'],
      503: ['Explicitly approved personal Gmail configuration required','Approved Gmail sandbox configuration required'],
    };
    return bareErrors[status]?.includes(payload.error) === true;
  }
  if (!exactKeys(payload,['error','upstream_status','upstream_reason']) || !Number.isInteger(payload.upstream_status) ||
      payload.upstream_status < 400 || payload.upstream_status > 599 ||
      !safeGmailReasons.has(payload.upstream_reason) || expectedOuterStatus(payload.upstream_status) !== status) return false;
  // The viewer controller's only specialized structured status-only response is
  // its exact validation failure. Other categorized errors are handled above.
  return status === 400 && payload.error === 'Invalid Gmail message viewer request' &&
    payload.upstream_status === 400 && payload.upstream_reason === 'UNSPECIFIED';
}
export async function request(path, token, { method = 'GET', body, signal, timeoutMs = 15000, clientSessionId } = {}) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(abort, timeoutMs);
  try {
    const response = await fetch(path, {
      method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(clientSessionId ? { 'X-Client-Session': clientSessionId } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}), signal: controller.signal,
      cache: 'no-store', credentials: 'omit', redirect: 'error',
    });
    if (!response.ok) {
      let payload = null;
      try { payload = await response.json(); } catch { /* Error bodies are never displayed directly. */ }
      const directGmailSearch = path === '/api/gmail/search/approval' || path === '/api/gmail/search';
      const directGmailViewer = path === '/api/gmail/messages/view-approval' || path === '/api/gmail/messages/view';
      const knownCategories = new Set(['GMAIL_SEARCH_REQUEST_TIMEOUT','GMAIL_SEARCH_CAPACITY_EXHAUSTED',
        'GMAIL_PROVIDER_AUTH_FAILURE','GMAIL_PROVIDER_PERMISSION_FAILURE','GMAIL_PROVIDER_RATE_LIMIT',
        'GMAIL_PROVIDER_QUOTA_EXCEEDED','GMAIL_PROVIDER_UNAVAILABLE','GMAIL_PROVIDER_REQUEST_FAILURE',
        'GMAIL_NETWORK_FAILURE','GMAIL_REQUEST_TIMEOUT','GMAIL_REQUEST_BUDGET_EXHAUSTED']);
      const viewerCategories = new Set(['GMAIL_VIEWER_CAPACITY_EXHAUSTED','GMAIL_UNEXPECTED_RETRIEVAL_FAILURE',
        'GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED']);
      const viewerAllowedCategories = new Set(['GMAIL_PROVIDER_AUTH_FAILURE','GMAIL_PROVIDER_PERMISSION_FAILURE',
        'GMAIL_PROVIDER_RATE_LIMIT','GMAIL_PROVIDER_UNAVAILABLE','GMAIL_PROVIDER_REQUEST_FAILURE',
        'GMAIL_NETWORK_FAILURE','GMAIL_REQUEST_TIMEOUT','GMAIL_REQUEST_BUDGET_EXHAUSTED',...viewerCategories]);
      const category = typeof payload?.error_category === 'string' && knownCategories.has(payload.error_category)
        ? payload.error_category
        : directGmailViewer && typeof payload?.error_category === 'string' && viewerCategories.has(payload.error_category)
          ? payload.error_category : '';
      const viewerHasSafeStatusOnlyError = directGmailViewer && viewerStatusOnlyContract(response.status,payload);
      const viewerContract = directGmailViewer ? viewerErrorContract(response.status,payload,category) : null;
      const viewerHasUnrecognizedError = directGmailViewer && (typeof payload?.error_category === 'string'
        ? !viewerAllowedCategories.has(payload.error_category) || viewerContract === null : !viewerHasSafeStatusOnlyError);
      const viewerMessage = viewerContract?.message || (viewerHasSafeStatusOnlyError && response.status === 403 ? 'This email is unavailable under the current Gmail access policy. Check the connected account’s access, then run a fresh search.'
        : viewerHasSafeStatusOnlyError && response.status === 400 ? 'The viewer request could not be validated. Run a fresh Gmail search and select the email again.'
        : '');
      const genericFailure = 'The backend could not complete the request. Check its health and try again.';
      const message = viewerMessage || (viewerHasUnrecognizedError ? genericFailure : (directGmailSearch && category === 'GMAIL_SEARCH_REQUEST_TIMEOUT' ? 'Gmail search timed out. No results were returned; submit a fresh search to try again.'
        : directGmailSearch && category === 'GMAIL_SEARCH_CAPACITY_EXHAUSTED' ? 'Gmail search is busy. Wait briefly, then submit a fresh search.'
        : directGmailSearch && category === 'GMAIL_PROVIDER_AUTH_FAILURE' ? 'Access token or approved Gmail account was rejected. Reconnect before searching again.'
        : response.status === 401 ? 'Access token rejected. Reconnect with the current local token.'
        : directGmailSearch && category.startsWith('GMAIL_PROVIDER_') ? 'Gmail could not complete this search. Check the connection and submit a fresh search if needed.'
        : directGmailSearch && payload?.error === 'Gmail search approval is invalid or expired' ? 'This search approval expired or is no longer valid. Start a fresh search.'
        : directGmailSearch && response.status === 400 ? 'Check the Gmail search filters and try again.'
        : directGmailSearch && response.status === 413 ? 'Gmail search filters exceed the request limit.'
        : response.status === 400 ? 'Check your question or filters. This request was not accepted.'
        : response.status === 413 ? 'The question is too large.'
        : genericFailure));
      throw new ApiError(response.status, message, category, viewerContract?.trustedProviderAuthFailure === true);
    }
    try { return await response.json(); }
    catch { throw new ApiError(502, 'The backend returned an unreadable response.'); }
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (signal?.aborted) throw error;
    throw new ApiError(0, 'The backend is unavailable or the request timed out. Check the local servers.');
  } finally {
    clearTimeout(timer); signal?.removeEventListener('abort', abort);
  }
}
export function validAnswer(value) {
  const metrics = value?.gmail_retrieval;
  const recentEmails = value?.recent_emails === undefined ? [] : value.recent_emails;
  const validMetrics = metrics == null || (metrics && Number.isSafeInteger(metrics.gmail_rest_attempts) &&
    Number.isSafeInteger(metrics.profile_requests) && Number.isSafeInteger(metrics.list_requests) &&
    Number.isSafeInteger(metrics.message_requests) && Number.isSafeInteger(metrics.attachment_requests) &&
    Number.isSafeInteger(metrics.oauth_refresh_attempts) && Number.isSafeInteger(metrics.response_bytes) &&
    Number.isSafeInteger(metrics.messages_retrieved) && metrics.profile_requests === 1 && metrics.list_requests === 1 &&
    metrics.message_requests === metrics.messages_retrieved && metrics.gmail_rest_attempts ===
      metrics.profile_requests + metrics.list_requests + metrics.message_requests + metrics.attachment_requests &&
    metrics.gmail_rest_attempts <= 27 && metrics.attachment_requests <= 5 && metrics.oauth_refresh_attempts <= 1 &&
    metrics.response_bytes >= 0 && metrics.response_bytes <= 10 * 1024 * 1024 &&
    metrics.messages_retrieved >= 0 && metrics.messages_retrieved <= 20);
  const validRecentEmails = Array.isArray(recentEmails) && recentEmails.length <= 10 &&
    recentEmails.every(row => row && typeof row === 'object' && !Array.isArray(row) &&
      Object.keys(row).sort().join(',') === 'excerpt,received_at,selection_id,sender,subject' &&
      typeof row.subject === 'string' && row.subject.length <= 512 &&
      typeof row.sender === 'string' && row.sender.length <= 512 &&
      typeof row.excerpt === 'string' && row.excerpt.length <= 240 &&
      (row.received_at == null || (typeof row.received_at === 'string' && !Number.isNaN(Date.parse(row.received_at)))) &&
      (row.selection_id == null || validViewSelection(row.selection_id)));
  return value && ['EVIDENCE_FOUND', 'INSUFFICIENT_EVIDENCE'].includes(value.status) &&
    value.mode === 'DETERMINISTIC_EXTRACTIVE' && value.llm_generated === false && value.external_processing === false &&
    typeof value.answer === 'string' && Array.isArray(value.evidence) && Array.isArray(value.limitations) && validMetrics && validRecentEmails &&
    (value.partial_results == null || typeof value.partial_results === 'boolean') &&
    value.limitations.every(x => typeof x === 'string') &&
    (recentEmails.length === 0 || (metrics != null && value.evidence.length === 0 && value.status === 'EVIDENCE_FOUND' &&
      Array.isArray(value.sources) && value.sources.includes('GMAIL') && value.limited_coverage === true &&
      recentEmails.length <= metrics.messages_retrieved)) &&
    (value.status === 'INSUFFICIENT_EVIDENCE' ? value.evidence.length === 0 && recentEmails.length === 0 :
      value.evidence.length > 0 || recentEmails.length > 0) &&
    value.evidence.every(e => e && ['GMAIL', 'DRIVE', 'WHATSAPP'].includes(e.source_type) &&
      ['reference', 'source_id', 'document_id', 'source_version', 'locator', 'excerpt'].every(k => typeof e[k] === 'string') &&
      ['connector_account_id','title','data_mode','view_selection_id'].every(k => e[k] == null || typeof e[k] === 'string'));
}
const VIEW_HANDLE = /^[A-Za-z0-9_-]{43}$/;
export function validViewSelection(value) { return typeof value === 'string' && VIEW_HANDLE.test(value); }
export function validApproval(value) { return value && Object.keys(value).length === 1 && validViewSelection(value.approval_token); }
export function validGmailDetail(value) {
  const strings = ['subject','sender','recipient'];
  const allowed = new Set(['subject','sender','recipient','received_at','body_text','body_available','incomplete',
    'body_truncated','attachment_names','attachment_names_truncated']);
  return value && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).every(key => allowed.has(key)) && strings.every(key =>
      value[key] == null || (typeof value[key] === 'string' && value[key].length <= 512)) &&
    (value.received_at == null || (typeof value.received_at === 'string' && !Number.isNaN(Date.parse(value.received_at)))) &&
    typeof value.body_text === 'string' && value.body_text.length <= 262144 &&
    typeof value.body_available === 'boolean' && typeof value.incomplete === 'boolean' &&
    typeof value.body_truncated === 'boolean' && typeof value.attachment_names_truncated === 'boolean' &&
    Array.isArray(value.attachment_names) && value.attachment_names.length <= 50 &&
    value.attachment_names.every(name => typeof name === 'string' && name.length <= 200);
}
export function validRecords(value) {
  return Array.isArray(value) && value.every(r => r && r.source && typeof r.source.document_id === 'string' &&
    typeof r.source.content_text === 'string' && typeof r.source.title === 'string' &&
    ['GMAIL','DRIVE','WHATSAPP'].includes(r.source.source_type) &&
    ['external_id','connector_account_id','occurred_at'].every(k => r.source[k] == null || typeof r.source[k] === 'string') &&
    ['classification','data_mode'].every(k => r[k] == null || typeof r[k] === 'string') &&
    (!r.citation || ['document_id','source_version','locator'].every(k => r.citation[k] == null || typeof r.citation[k] === 'string')));
}
export function validSyncResult(value) {
  const count = n => Number.isSafeInteger(n) && n >= 0;
  return value && ['BASELINE_ESTABLISHED','NO_CHANGES','SYNCHRONIZED'].includes(value.status) &&
    ['inserted','updated','removed','gmail_rest_attempts'].every(key => count(value[key])) &&
    value.inserted + value.updated + value.removed <= 5 && value.gmail_rest_attempts <= 12 &&
    typeof value.replayed === 'boolean' && value.full_resynchronization === false;
}
