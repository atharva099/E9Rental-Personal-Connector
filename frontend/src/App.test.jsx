import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import App from './App.jsx';
const TOKEN = 'synthetic-ui-test-token-not-a-real-credential';
const citation = { reference:'S1',source_type:'GMAIL',source_id:'synthetic-mail-1',document_id:'synthetic-doc-1',
  source_version:'v1',connector_account_id:'synthetic-account',locator:'synthetic:message:1',title:'Synthetic quotation',
  data_mode:'SYNTHETIC',excerpt:'Please share pricing',start_offset:0,end_offset:20 };
const answer = {status:'EVIDENCE_FOUND',mode:'DETERMINISTIC_EXTRACTIVE',llm_generated:false,external_processing:false,
  answer:'[S1] Please share pricing',evidence:[citation],limited_coverage:false,limitations:['Exact recorded evidence only.']};
const VIEW_HANDLE='v'.repeat(43), APPROVAL_TOKEN='p'.repeat(43);
const directAnswer = (selection=VIEW_HANDLE) => ({...answer,
  evidence:[{...citation,data_mode:'LIVE',view_selection_id:selection}],
  gmail_retrieval:{gmail_rest_attempts:3,profile_requests:1,list_requests:1,message_requests:1,attachment_requests:0,
    oauth_refresh_attempts:0,response_bytes:512,messages_retrieved:1}});
const recentListAnswer = (selection=VIEW_HANDLE, firstRow={}) => ({status:'EVIDENCE_FOUND',mode:'DETERMINISTIC_EXTRACTIVE',llm_generated:false,external_processing:false,
  answer:'Showing up to 10 recent Gmail messages from this bounded request. This is not a complete mailbox listing.',evidence:[],sources:['GMAIL'],
  limited_coverage:true,limitations:['Bounded recent Gmail results.'],partial_results:true,
  gmail_retrieval:{gmail_rest_attempts:4,profile_requests:1,list_requests:1,message_requests:2,attachment_requests:0,
    oauth_refresh_attempts:0,response_bytes:512,messages_retrieved:2},
  recent_emails:[{subject:'Synthetic latest subject',sender:'sender@example.test',received_at:'2026-10-19T12:30:00Z',
    excerpt:'<img src=x onerror=alert(1)>',selection_id:selection,...firstRow},
    {subject:'Synthetic second subject',sender:'second@example.test',received_at:'2026-10-18T12:30:00Z',excerpt:'',selection_id:null}]});
const emailDetail = {subject:'Synthetic full email',sender:'sender@example.test',recipient:'reader@example.test',
  received_at:'2026-10-19T12:30:00Z',body_text:'Synthetic full body\nSecond line',body_available:true,incomplete:false,
  body_truncated:false,attachment_names:['synthetic.pdf'],attachment_names_truncated:false};
const gmailRow = {subject:'Synthetic interview',sender:'recruiter@example.test',received_at:'2026-10-19T12:30:00Z',
  excerpt:'Please confirm a convenient interview time.',selection_id:VIEW_HANDLE};
const gmailResults = {results:[gmailRow],partial_results:false,coverage_warning:null};
const response = (data,status=200) => ({ok:status>=200 && status<300,status,json:async()=>data});
let qa, approveSearch, gmailSearch, sync, approveView, viewEmail, activeMode;
beforeEach(()=>{
  activeMode='SANDBOX';
  vi.stubGlobal('confirm',vi.fn(()=>true));
  qa=async()=>response(answer);approveSearch=async()=>response({approval_token:APPROVAL_TOKEN});gmailSearch=async()=>response(gmailResults);
  sync=async()=>response({status:'SYNCHRONIZED',inserted:1,updated:2,removed:1,replayed:false,full_resynchronization:false,gmail_rest_attempts:4});
  approveView=async()=>response({approval_token:APPROVAL_TOKEN});viewEmail=async()=>response(emailDetail);
  vi.stubGlobal('fetch',vi.fn(async(path,options)=>{
    if(path==='/health')return response({status:'UP'});
    if(path==='/api/capabilities')return response({mode:activeMode});
    if(path==='/api/gmail/search/approval')return approveSearch(path,options);
    if(path==='/api/gmail/search')return gmailSearch(path,options);
    if(path==='/api/qa/ask')return qa(path,options);
    if(path==='/api/gmail/sync')return sync(path,options);
    if(path==='/api/gmail/messages/view-approval')return approveView(path,options);
    if(path==='/api/gmail/messages/view')return viewEmail(path,options);
    return response([]);
  }));
});
afterEach(()=>vi.unstubAllGlobals());
async function connected(mode='SANDBOX'){
  activeMode=mode;
  const user=userEvent.setup();render(<App/>);
  await user.type(screen.getByLabelText('Local access token'),TOKEN);
  await user.click(screen.getByRole('button',{name:'Connect',exact:true}));
  await screen.findByText(mode==='PERSONAL'?'Personal Gmail connected':'Local session connected');return user;
}
async function ask(user,text='Show quotation records from Gmail'){
  await user.type(screen.getByLabelText('Your question'),text);
  await user.click(screen.getByRole('button',{name:'Ask question'}));
}
describe('local connector workspace',()=>{
  it('does not expose mode-specific controls before authenticated mode detection',async()=>{
    render(<App/>);
    expect(screen.queryByRole('heading',{name:'Gmail'})).not.toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'Ask question'})).not.toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'Search Gmail'})).not.toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'Sync Gmail now'})).not.toBeInTheDocument();
    await screen.findByText('Backend online');
  });
  it('connects through the authenticated capabilities endpoint without querying the unified index',async()=>{
    const user=await connected('PERSONAL');
    const capabilityCall=fetch.mock.calls.find(([path])=>path==='/api/capabilities');
    expect(capabilityCall[1].headers.Authorization).toBe('Bearer '+TOKEN);
    expect(capabilityCall[1].cache).toBe('no-store');
    expect(fetch.mock.calls.some(([path])=>path.startsWith('/api/unified/search'))).toBe(false);
    expect(user).toBeTruthy();
  });
  it('fails closed for an unknown backend mode without exposing mode-specific features',async()=>{
    activeMode='UNKNOWN';
    const user=userEvent.setup();render(<App/>);
    await user.type(screen.getByLabelText('Local access token'),TOKEN);
    await user.click(screen.getByRole('button',{name:'Connect',exact:true}));
    expect(await screen.findByRole('alert')).toHaveTextContent('mode could not be verified safely');
    expect(screen.queryByRole('heading',{name:'Search Gmail'})).not.toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'Sync Gmail now'})).not.toBeInTheDocument();
    expect(screen.getByLabelText('Local access token')).toHaveValue(TOKEN);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/capabilities')).toHaveLength(1);
  });
  it('fails closed for malformed capability responses and never displays backend details',async()=>{
    fetch.mockImplementation(async path=>path==='/health'?response({status:'UP'}):
      path==='/api/capabilities'?response({mode:'PERSONAL',detail:'PRIVATE_CONFIGURATION'}):response({}));
    const user=userEvent.setup();render(<App/>);
    await user.type(screen.getByLabelText('Local access token'),TOKEN);
    await user.click(screen.getByRole('button',{name:'Connect',exact:true}));
    expect(await screen.findByRole('alert')).toHaveTextContent('mode could not be verified safely');
    expect(screen.queryByText('PRIVATE_CONFIGURATION')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading',{name:'Ask Gmail'})).not.toBeInTheDocument();
  });
  it('presents the Personal workspace as Live Gmail only while preserving the Sandbox experience',async()=>{
    const personal=await connected('PERSONAL');
    expect(screen.getByRole('heading',{name:'Ask Gmail'})).toBeInTheDocument();
    expect(screen.getByRole('heading',{name:'Search Gmail'})).toBeInTheDocument();
    expect(screen.getByLabelText('Live Gmail-only workspace')).toHaveTextContent('does not delete previously stored data');
    expect(screen.queryByRole('checkbox',{name:/Search Gmail now/})).not.toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'Sync Gmail now'})).not.toBeInTheDocument();
    expect(screen.queryByRole('heading',{name:'Google Drive'})).not.toBeInTheDocument();
    expect(screen.queryByRole('heading',{name:'WhatsApp'})).not.toBeInTheDocument();
    await personal.click(screen.getByRole('button',{name:'Disconnect'}));
    cleanup();
    await connected('SANDBOX');
    expect(screen.getByRole('heading',{name:'Gmail'})).toBeInTheDocument();
    expect(screen.getByRole('heading',{name:'Google Drive'})).toBeInTheDocument();
    expect(screen.getByRole('heading',{name:'WhatsApp'})).toBeInTheDocument();
    expect(screen.getByRole('checkbox',{name:/Search Gmail now/})).toBeInTheDocument();
    expect(screen.getByRole('button',{name:'Sync Gmail now'})).toBeInTheDocument();
  });
  it('requires confirmation and uses explicitly authorized live Gmail Ask in Personal mode',async()=>{
    const user=await connected('PERSONAL');
    await user.type(screen.getByLabelText('Your question'),'Find Gmail emails about invoice');
    expect(confirm).not.toHaveBeenCalled();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/qa/ask')).toHaveLength(0);
    await user.click(screen.getByRole('button',{name:'Ask Gmail'}));
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole('heading',{name:/Supporting citations/})).toBeInTheDocument();
    const calls=fetch.mock.calls.filter(([path])=>path==='/api/qa/ask');
    expect(calls).toHaveLength(1);
    expect(JSON.parse(calls[0][1].body)).toEqual({question:'Find Gmail emails about invoice',searchGmailNow:true});
  });
  it('renders recent Ask results as safe structured rows with bounded coverage disclosure',async()=>{
    qa=async()=>response(recentListAnswer());
    const user=await connected('PERSONAL');
    await user.type(screen.getByLabelText('Your question'),'Show my latest 10 Gmail emails');
    await user.click(screen.getByRole('button',{name:'Ask Gmail'}));
    expect(await screen.findByRole('heading',{name:/Recent Gmail messages/})).toBeInTheDocument();
    expect(screen.getByText('Gmail-backed request · retrieved for this question only.')).toBeInTheDocument();
    expect(screen.getByText(/Recent-email questions show structured rows; other supported questions show cited evidence/)).toBeInTheDocument();
    expect(screen.getByText(/Results are bounded and may not cover the complete mailbox/)).toBeInTheDocument();
    expect(screen.getByText('Synthetic latest subject')).toBeInTheDocument();
    expect(screen.getByText('sender@example.test')).toBeInTheDocument();
    expect(screen.getByText(/19 Oct 2026/)).toBeInTheDocument();
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    expect(screen.getByText('No readable preview is available.')).toBeInTheDocument();
    expect(screen.getByText(/up to 10 messages · not complete mailbox coverage/)).toBeInTheDocument();
    expect(screen.getByText(/Gmail returned more matches/)).toBeInTheDocument();
    expect(screen.queryByRole('heading',{name:/Supporting citations/})).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/qa/ask')).toHaveLength(1);
    expect(fetch.mock.calls.some(([path])=>path.startsWith('/api/gmail/search'))).toBe(false);
  });
  it('renders all ten synthetic recent Gmail rows',async()=>{
    const base=recentListAnswer();
    const rows=Array.from({length:10},(_,index)=>({subject:`Synthetic recent subject ${index+1}`,sender:`sender${index+1}@example.test`,
      received_at:`2026-10-${String(20-index).padStart(2,'0')}T12:00:00Z`,excerpt:`Synthetic preview ${index+1}`,
      selection_id:String.fromCharCode(65+index).repeat(43)}));
    qa=async()=>response({...base,gmail_retrieval:{...base.gmail_retrieval,gmail_rest_attempts:12,message_requests:10,messages_retrieved:10},recent_emails:rows});
    const user=await connected('PERSONAL');
    await user.type(screen.getByLabelText('Your question'),'Show my latest 10 Gmail emails');
    await user.click(screen.getByRole('button',{name:'Ask Gmail'}));
    expect(await screen.findByRole('heading',{name:/Recent Gmail messages/})).toBeInTheDocument();
    expect(document.querySelectorAll('.recent-gmail-row')).toHaveLength(10);
    for(let index=1;index<=10;index++)expect(screen.getByText(`Synthetic recent subject ${index}`)).toBeInTheDocument();
    expect(screen.getByText(/not complete mailbox coverage/)).toBeInTheDocument();
  });  it('shows safe fallbacks when recent Gmail row labels and received time are missing',async()=>{
    qa=async()=>response(recentListAnswer(null,{subject:'',sender:'',received_at:null,excerpt:''}));
    const user=await connected('PERSONAL');
    await user.type(screen.getByLabelText('Your question'),'Show my latest 10 Gmail emails');
    await user.click(screen.getByRole('button',{name:'Ask Gmail'}));
    expect(await screen.findByText('Subject unavailable')).toBeInTheDocument();
    expect(screen.getByText('Sender unavailable')).toBeInTheDocument();
    expect(screen.getByText('Date unavailable')).toBeInTheDocument();
    expect(screen.getAllByText('No readable preview is available.')).toHaveLength(2);
    expect(screen.queryByRole('button',{name:'View full email'})).not.toBeInTheDocument();
    expect(screen.getByText(/not complete mailbox coverage/)).toBeInTheDocument();
  });
  it('uses the recent row opaque handle with a separate fresh viewer confirmation and approval',async()=>{
    qa=async()=>response(recentListAnswer());
    const user=await connected('PERSONAL');
    await user.type(screen.getByLabelText('Your question'),'Show my latest 10 Gmail emails');
    await user.click(screen.getByRole('button',{name:'Ask Gmail'}));
    const button=await screen.findByRole('button',{name:'View full email'});
    expect(confirm).toHaveBeenCalledTimes(1);
    await user.click(button);
    expect(confirm).toHaveBeenCalledTimes(2);
    const viewerCalls=fetch.mock.calls.filter(([path])=>path.startsWith('/api/gmail/messages/'));
    expect(viewerCalls.map(([path])=>path)).toEqual(['/api/gmail/messages/view-approval','/api/gmail/messages/view']);
    expect(JSON.parse(viewerCalls[0][1].body)).toEqual({selection_id:VIEW_HANDLE});
    expect(JSON.parse(viewerCalls[1][1].body)).toEqual({approval_token:APPROVAL_TOKEN});
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
  it('cancelling the viewer from a recent Gmail row causes no approval or detail request',async()=>{
    qa=async()=>response(recentListAnswer());
    const user=await connected('PERSONAL');
    await user.type(screen.getByLabelText('Your question'),'Show my latest 10 Gmail emails');
    await user.click(screen.getByRole('button',{name:'Ask Gmail'}));
    const button=await screen.findByRole('button',{name:'View full email'});
    confirm.mockReturnValue(false);confirm.mockClear();
    await user.click(button);
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls.filter(([path])=>path.startsWith('/api/gmail/messages/'))).toHaveLength(0);
  });
  it('keeps Search Gmail and the separately approved Full Email Viewer available in Personal mode',async()=>{
    const user=await connected('PERSONAL');
    await user.type(screen.getByLabelText('Keyword'),'interview');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await screen.findByRole('heading',{name:'Synthetic interview'});
    expect(confirm).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button',{name:'View full email'}));
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    const providerRoutes=fetch.mock.calls.map(([path])=>path).filter(path=>path.startsWith('/api/gmail/'));
    expect(providerRoutes).toEqual(['/api/gmail/search/approval','/api/gmail/search',
      '/api/gmail/messages/view-approval','/api/gmail/messages/view']);
  });
  it('keeps token out of persistent storage, clears token input and supplies Authorization',async()=>{
    const local=vi.spyOn(Storage.prototype,'setItem');const user=await connected();
    expect(screen.queryByLabelText('Local access token')).not.toBeInTheDocument();
    await ask(user);
    expect(confirm).not.toHaveBeenCalled();
    await screen.findByRole('heading',{name:/Supporting citations/});
    const call=fetch.mock.calls.find(([path])=>path==='/api/qa/ask');
    expect(call[1].headers.Authorization).toBe('Bearer '+TOKEN);
    expect(JSON.parse(call[1].body)).toEqual({question:'Show quotation records from Gmail'});
    expect(call[1].credentials).toBe('omit');expect(local).not.toHaveBeenCalled();local.mockRestore();
  });
  it('renders answer citations while keeping technical source identifiers collapsed',async()=>{
    const user=await connected();await ask(user);
    await screen.findByText('S1 · Gmail');
    expect(screen.queryByText('synthetic-mail-1')).not.toBeInTheDocument();
    expect(screen.queryByText('synthetic:message:1')).not.toBeInTheDocument();
    expect(screen.getByText('SYNTHETIC · practice data')).toBeInTheDocument();
    expect(screen.getByText('Please share pricing',{selector:'blockquote'})).toBeInTheDocument();
  });
  it('labels transient live Gmail evidence as retrieved for this question',async()=>{
    qa=async()=>response({...answer,evidence:[{...citation,data_mode:'LIVE'}],gmail_retrieval:{gmail_rest_attempts:11,profile_requests:1,list_requests:1,message_requests:9,attachment_requests:0,oauth_refresh_attempts:1,response_bytes:0,messages_retrieved:9}});
    const user=await connected();await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));
    await ask(user,'Show my Gmail emails from yesterday');
    expect(await screen.findByText('LIVE · retrieved for this question')).toBeInTheDocument();
    expect(screen.queryByText('LIVE · stored snapshot')).not.toBeInTheDocument();
  });
  it('labels Search Gmail as live and does not search Gmail on page load or filter edits',async()=>{
    const user=await connected();
    expect(screen.getByRole('heading',{name:'Search Gmail'})).toBeInTheDocument();
    expect(screen.queryByText('Search your records')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search/approval'||path==='/api/gmail/search')).toHaveLength(0);
    await user.type(screen.getByLabelText('Keyword'),'interview');
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search/approval'||path==='/api/gmail/search')).toHaveLength(0);
    expect(fetch.mock.calls.some(([path])=>path.startsWith('/api/unified/search?keyword='))).toBe(false);
  });
  it('validates typed filters locally and sends no approval or search for unsafe input',async()=>{
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'interview*');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    expect(screen.getByRole('alert')).toHaveTextContent('alphanumeric');
    expect(confirm).not.toHaveBeenCalled();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search/approval'||path==='/api/gmail/search')).toHaveLength(0);
  });
  it('requires a complete date pair before requesting fresh approval',async()=>{
    const user=await connected();fireEvent.change(screen.getByLabelText('From date'),{target:{value:'2026-10-01'}});
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    expect(screen.getByRole('alert')).toHaveTextContent('both dates');
    expect(confirm).not.toHaveBeenCalled();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search/approval'||path==='/api/gmail/search')).toHaveLength(0);
  });
  it('asks once and then submits validated filters through approval before search',async()=>{
    const user=await connected();
    await user.type(screen.getByLabelText('Keyword'),'interview salary');
    await user.type(screen.getByLabelText('Exact sender email'),'Recruiter@example.test');
    fireEvent.change(screen.getByLabelText('From date'),{target:{value:'2026-10-01'}});
    fireEvent.change(screen.getByLabelText('To date'),{target:{value:'2026-10-18'}});
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await screen.findByRole('heading',{name:'Synthetic interview'});
    expect(confirm).toHaveBeenCalledTimes(1);
    const calls=fetch.mock.calls.filter(([path])=>path==='/api/gmail/search/approval'||path==='/api/gmail/search');
    expect(calls.map(([path])=>path)).toEqual(['/api/gmail/search/approval','/api/gmail/search']);
    expect(JSON.parse(calls[0][1].body)).toEqual({keyword:'interview salary',sender:'Recruiter@example.test',start_date:'2026-10-01',end_date:'2026-10-18'});
    expect(JSON.parse(calls[1][1].body)).toEqual({approval_token:APPROVAL_TOKEN});
    expect(calls[0][1].headers.Authorization).toBe('Bearer '+TOKEN);
    expect(calls[0][1].headers['X-Client-Session']).toBeTruthy();
    expect(calls[0][1].headers['X-Client-Session']).toBe(calls[1][1].headers['X-Client-Session']);
    expect(calls.every(([,options])=>options.cache==='no-store'&&options.credentials==='omit')).toBe(true);
  });
  it('cancelling a live Gmail search makes zero approval and search requests',async()=>{
    vi.stubGlobal('confirm',vi.fn(()=>false));const user=await connected();
    await user.type(screen.getByLabelText('Keyword'),'interview');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search/approval'||path==='/api/gmail/search')).toHaveLength(0);
  });
  it('renders transient fields and partial coverage as escaped text',async()=>{
    const malicious='<img src=x onerror=alert(1)>';
    gmailSearch=async()=>response({results:[{...gmailRow,excerpt:malicious}],partial_results:true,
      coverage_warning:'More matching emails may exist.'});
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'interview');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await screen.findByRole('heading',{name:'Synthetic interview'});
    expect(screen.getByText('recruiter@example.test')).toBeInTheDocument();
    expect(screen.getByText(/Oct 19, 2026|19 Oct 2026/)).toBeInTheDocument();
    expect(screen.getByText(malicious)).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent(/More matching emails may exist/);
  });
  it('submits a fresh search approval for each confirmed search',async()=>{
    const firstApproval='a'.repeat(43),secondApproval='b'.repeat(43);
    approveSearch=vi.fn().mockResolvedValueOnce(response({approval_token:firstApproval})).mockResolvedValueOnce(response({approval_token:secondApproval}));
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'interview');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));await screen.findByRole('heading',{name:'Synthetic interview'});
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));await screen.findByRole('heading',{name:'Synthetic interview'});
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search/approval')).toHaveLength(2);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search').map(([,options])=>JSON.parse(options.body).approval_token))
      .toEqual([firstApproval,secondApproval]);
  });
  it('prevents duplicate search approval submissions while a confirmed request is pending',async()=>{
    let finish;gmailSearch=()=>new Promise(resolve=>{finish=resolve;});
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'interview');
    const form=screen.getByRole('button',{name:'Search Gmail'}).closest('form');
    fireEvent.submit(form);fireEvent.submit(form);
    expect(confirm).toHaveBeenCalledTimes(1);
    await waitFor(()=>expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search/approval')).toHaveLength(1));
    await waitFor(()=>expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search')).toHaveLength(1));
    await act(async()=>finish(response(gmailResults)));
    await screen.findByRole('heading',{name:'Synthetic interview'});
  });
  it('opens a search result only through the existing fresh full-email approval flow',async()=>{
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'interview');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));await screen.findByRole('button',{name:'View full email'});
    await user.click(screen.getByRole('button',{name:'View full email'}));
    expect(await screen.findByRole('dialog')).toHaveTextContent('Synthetic full body');
    expect(confirm).toHaveBeenCalledTimes(2);
    const viewApproval=fetch.mock.calls.find(([path])=>path==='/api/gmail/messages/view-approval');
    expect(JSON.parse(viewApproval[1].body)).toEqual({selection_id:VIEW_HANDLE});
    expect(fetch.mock.calls.some(([path])=>path==='/api/gmail/messages/view')).toBe(true);
  });
  it.each([
    [{error:'busy',error_category:'GMAIL_SEARCH_CAPACITY_EXHAUSTED'},503,/Gmail search is busy/],
    [{error:'timeout',error_category:'GMAIL_SEARCH_REQUEST_TIMEOUT'},503,/Gmail search timed out/],
    [{error:'private provider detail',error_category:'GMAIL_PROVIDER_UNAVAILABLE'},502,/Gmail could not complete this search/],
    [{error:'private provider response'},502,/backend could not complete/],
    [{error:'bad filters'},400,/Check the Gmail search filters/],
    [{error:'large body'},413,/exceed the request limit/],
    [{error:'Gmail search approval is invalid or expired'},502,/approval expired/],
  ])('shows a safe search error for %o',async(payload,status,message)=>{
    gmailSearch=async()=>response(payload,status);const user=await connected();
    await user.type(screen.getByLabelText('Keyword'),'interview');await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(screen.queryByText(/private provider/)).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search/approval')).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/search')).toHaveLength(1);
  });
  it('clears search rows and viewer handles after an authentication failure',async()=>{
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'interview');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));await screen.findByRole('heading',{name:'Synthetic interview'});
    gmailSearch=async()=>response({error:'unauthorized'},401);
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await screen.findByLabelText('Local access token');
    expect(screen.queryByRole('heading',{name:'Synthetic interview'})).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('rejected');
  });
  it('clears search state when the approved Gmail account is rejected',async()=>{
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'interview');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));await screen.findByRole('heading',{name:'Synthetic interview'});
    gmailSearch=async()=>response({error:'account mismatch',error_category:'GMAIL_PROVIDER_AUTH_FAILURE'},401);
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await screen.findByLabelText('Local access token');
    expect(screen.queryByRole('heading',{name:'Synthetic interview'})).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('approved Gmail account was rejected');
  });
  it('does not persist live result content or approvals in browser storage',async()=>{
    const setItem=vi.spyOn(Storage.prototype,'setItem');const user=await connected();
    await user.type(screen.getByLabelText('Keyword'),'interview');await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await screen.findByRole('heading',{name:'Synthetic interview'});
    expect(setItem).not.toHaveBeenCalled();
    expect(screen.queryByText(VIEW_HANDLE)).not.toBeInTheDocument();setItem.mockRestore();
  });
  it('keeps Gmail lookup opt-in off by default and labels an explicit Gmail-backed request',async()=>{
    const user=await connected();
    const option=screen.getByRole('checkbox',{name:/Search Gmail now/});
    expect(option).not.toBeChecked();
    expect(screen.getByText(/Find Gmail emails from recruiter@example\.com about interview between 2026-10-01 and 2026-10-18/)).toBeInTheDocument();
    expect(screen.getByText(/at most 20 messages are retrieved/)).toBeInTheDocument();
    await ask(user);
    let call=fetch.mock.calls.find(([path])=>path==='/api/qa/ask');
    expect(JSON.parse(call[1].body)).toEqual({question:'Show quotation records from Gmail'});
    expect(await screen.findByText('Local index only')).toBeInTheDocument();
    await user.click(option);await user.clear(screen.getByLabelText('Your question'));
    await user.type(screen.getByLabelText('Your question'),'Show Gmail records about quotation');
    await user.click(screen.getByRole('button',{name:'Ask question'}));
    await screen.findByText(/Gmail-backed request.*retrieved for this question only/);
    call=fetch.mock.calls.filter(([path])=>path==='/api/qa/ask').at(-1);
    expect(JSON.parse(call[1].body)).toEqual({question:'Show Gmail records about quotation',searchGmailNow:true});
    expect(confirm).toHaveBeenCalledTimes(1);
  });
  it('cancelling the per-request Gmail confirmation sends no Ask request',async()=>{
    const confirmation=vi.fn(()=>false);vi.stubGlobal('confirm',confirmation);
    const user=await connected();await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));
    await user.type(screen.getByLabelText('Your question'),'Show Gmail records about quotation');
    await user.click(screen.getByRole('button',{name:'Ask question'}));
    expect(confirmation).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/qa/ask')).toHaveLength(0);
    expect(screen.getByLabelText('Your question')).toHaveValue('Show Gmail records about quotation');
  });
  it('asks for a fresh confirmation for every separate live question',async()=>{
    const confirmation=vi.fn(()=>true);vi.stubGlobal('confirm',confirmation);
    const user=await connected();await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));
    await ask(user,'Show Gmail records about quotation');
    await screen.findByText(/Gmail-backed request.*retrieved for this question only/);
    await user.clear(screen.getByLabelText('Your question'));
    await ask(user,'Show Gmail records about invoice');
    await screen.findByText(/Gmail-backed request.*retrieved for this question only/);
    expect(confirmation).toHaveBeenCalledTimes(2);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/qa/ask')).toHaveLength(2);
  });
  it('prevents duplicate live submissions while the single confirmed request is pending',async()=>{
    let finish;qa=()=>new Promise(resolve=>{finish=resolve;});
    const user=await connected();await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));
    await user.type(screen.getByLabelText('Your question'),'Show Gmail records about quotation');
    const form=screen.getByLabelText('Your question').closest('form');
    fireEvent.submit(form);fireEvent.submit(form);
    expect(confirm).toHaveBeenCalledTimes(1);
    await waitFor(()=>expect(fetch.mock.calls.filter(([path])=>path==='/api/qa/ask')).toHaveLength(1));
    await act(async()=>finish(response(answer)));
  });
  it('shows the explicit partial Gmail coverage warning with its citation',async()=>{
    qa=async()=>response({...answer,partial_results:true,limited_coverage:true,
      answer:answer.answer+'\nPartial Gmail coverage: additional matching emails may exist.'});
    const user=await connected();await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));
    await ask(user,'Show Gmail records about quotation');
    expect(await screen.findByText(/Gmail returned more matches/)).toBeInTheDocument();
    expect(screen.getByText('S1 · Gmail')).toBeInTheDocument();
  });
  it('shows loading and then explicit no-evidence without fake citations',async()=>{
    let finish;qa=()=>new Promise(resolve=>{finish=resolve;});
    const user=await connected();await ask(user);
    expect(screen.getByRole('button',{name:/Finding evidence/})).toBeDisabled();
    finish(response({...answer,status:'INSUFFICIENT_EVIDENCE',answer:'The answer cannot be determined from available records.',evidence:[]}));
    await screen.findByRole('heading',{name:'Not enough evidence'});
    expect(screen.queryByRole('heading',{name:/Supporting citations/})).not.toBeInTheDocument();
  });
  it('shows a safe error instead of raw backend contents',async()=>{
    qa=async()=>response({error:'PRIVATE_SERVER_CONTENT'},503);
    const user=await connected();await ask(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(screen.queryByText('PRIVATE_SERVER_CONTENT')).not.toBeInTheDocument();
  });
  it('preserves existing Ask validation error wording outside direct Gmail Search',async()=>{
    qa=async()=>response({error:'private validation detail'},400);
    const user=await connected();await ask(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Check your question or filters. This request was not accepted.');
    expect(screen.queryByText('private validation detail')).not.toBeInTheDocument();
  });
  it('clears session and evidence after an authentication failure',async()=>{
    const user=await connected();await ask(user);await screen.findByText('S1 · Gmail');
    qa=async()=>response({},401);
    await user.click(screen.getByRole('button',{name:'Ask question'}));
    await screen.findByLabelText('Local access token');
    expect(screen.queryByText('synthetic-mail-1')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Access token rejected');
  });
  it('keeps the local session when Ask reports a validated Gmail provider-auth rejection',async()=>{
    activeMode='PERSONAL';
    qa=async()=>response({error:'Gmail question retrieval failed',error_category:'GMAIL_PROVIDER_AUTH_FAILURE',
      upstream_status:401,upstream_reason:'UNSPECIFIED'},401);
    const user=await connected('PERSONAL');
    await user.type(screen.getByLabelText('Your question'),'Show my latest Gmail emails');
    await user.click(screen.getByRole('button',{name:'Ask Gmail'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('Gmail did not accept the current authorization');
    expect(screen.getByText('Personal Gmail connected')).toBeInTheDocument();
    expect(screen.queryByLabelText('Local access token')).not.toBeInTheDocument();
    const capabilityCall=fetch.mock.calls.find(([path])=>path==='/api/capabilities');
    const askCall=fetch.mock.calls.find(([path])=>path==='/api/qa/ask');
    expect(capabilityCall[1].headers.Authorization).toBe('Bearer '+TOKEN);
    expect(askCall[1].headers.Authorization).toBe(capabilityCall[1].headers.Authorization);
    expect(askCall[1].headers['X-Client-Session']).toMatch(/^[0-9a-f-]{36}$/i);
  });
  it('renders empty Gmail results and rejects malformed answer payloads',async()=>{
    gmailSearch=async()=>response({results:[],partial_results:false,coverage_warning:null});qa=async()=>response({answer:'UNTRUSTED_FORMAT'});
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'invoice');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await screen.findByText('No Gmail messages matched these filters.');await ask(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Unexpected answer format');
    expect(screen.queryByText('UNTRUSTED_FORMAT')).not.toBeInTheDocument();
  });
  it('escapes malicious source text without creating HTML or external links',async()=>{
    const malicious='<img src=x onerror=alert(1)>';
    qa=async()=>response({...answer,answer:malicious,evidence:[{...citation,excerpt:malicious,source_url:'javascript:alert(1)'}]});
    const user=await connected();await ask(user);await screen.findByText('S1 · Gmail');
    expect(document.querySelector('img')).toBeNull();
    expect(document.querySelector('a[href^="javascript:"]')).toBeNull();
    expect(screen.getAllByText(malicious)).toHaveLength(2);
  });
  it('does not repopulate results from a late response after disconnect',async()=>{
    let finish;qa=()=>new Promise(resolve=>{finish=resolve;});
    const user=await connected();await ask(user);
    await user.click(screen.getByRole('button',{name:'Disconnect'}));
    finish(response(answer));await waitFor(()=>expect(screen.getByLabelText('Local access token')).toHaveValue(''));
    expect(screen.queryByText('S1 · Gmail')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Your question')).not.toBeInTheDocument();
  });
  it('rejects short tokens locally and validates failed connections',async()=>{
    const user=userEvent.setup();render(<App/>);
    await user.type(screen.getByLabelText('Local access token'),'short');await user.click(screen.getByRole('button',{name:'Connect',exact:true}));
    expect(screen.getByRole('alert')).toHaveTextContent('at least 32');
    expect(fetch.mock.calls.filter(([p])=>p.startsWith('/api/'))).toHaveLength(0);
    fetch.mockImplementation(async p=>p==='/health'?response({status:'UP'}):response({},401));
    await user.clear(screen.getByLabelText('Local access token'));await user.type(screen.getByLabelText('Local access token'),TOKEN);
    await user.click(screen.getByRole('button',{name:'Connect',exact:true}));
    expect(await screen.findByRole('alert')).toHaveTextContent('Access token rejected');
    expect(screen.getByLabelText('Local access token')).toHaveValue('');
  });
  it('does not restore a canceled connection from a late capability response',async()=>{
    let finishCapabilities;
    fetch.mockImplementation((path)=>path==='/health'?Promise.resolve(response({status:'UP'})):
      path==='/api/capabilities'?new Promise(resolve=>{finishCapabilities=resolve;}):Promise.resolve(response({})));
    const user=userEvent.setup();render(<App/>);
    await user.type(screen.getByLabelText('Local access token'),TOKEN);
    await user.click(screen.getByRole('button',{name:'Connect',exact:true}));
    expect(screen.getByText('Connecting…')).toBeInTheDocument();
    await user.click(screen.getByRole('button',{name:'Cancel connection'}));
    await act(async()=>finishCapabilities(response({mode:'PERSONAL'})));
    expect(screen.getByLabelText('Local access token')).toHaveValue('');
    expect(screen.queryByRole('heading',{name:'Ask Gmail'})).not.toBeInTheDocument();
    expect(screen.queryByRole('heading',{name:'Search Gmail'})).not.toBeInTheDocument();
  });
  it('runs Gmail synchronization only after explicit user action and displays aggregate changes',async()=>{
    const user=await connected();
    expect(screen.getByRole('button',{name:'Sync Gmail now'})).toBeEnabled();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/sync')).toHaveLength(0);
    await user.click(screen.getByRole('button',{name:'Sync Gmail now'}));
    expect(confirm).not.toHaveBeenCalled();
    expect(await screen.findByRole('status')).toHaveTextContent('Sync complete: 1 added, 2 updated, 1 removed.');
    const calls=fetch.mock.calls.filter(([path])=>path==='/api/gmail/sync');
    expect(calls).toHaveLength(1);
    expect(calls[0][1].method).toBe('POST');
    expect(calls[0][1].headers.Authorization).toBe('Bearer '+TOKEN);
    expect(calls[0][1].body).toBeUndefined();
  });
  it('prevents repeated sync clicks while the request is pending',async()=>{
    let resolveSync;
    sync=()=>new Promise(resolve=>{resolveSync=resolve;});
    const user=await connected();const button=screen.getByRole('button',{name:'Sync Gmail now'});
    await user.click(button);
    expect(button).toBeDisabled();
    await user.click(button);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/sync')).toHaveLength(1);
    await act(async()=>resolveSync(response({status:'NO_CHANGES',inserted:0,updated:0,removed:0,replayed:false,full_resynchronization:false,gmail_rest_attempts:2})));
    expect(await screen.findByRole('status')).toHaveTextContent('No Gmail changes were found since the last sync.');
  });
  it('describes a baseline as cursor setup without historical import',async()=>{
    sync=async()=>response({status:'BASELINE_ESTABLISHED',inserted:0,updated:0,removed:0,replayed:false,full_resynchronization:false,gmail_rest_attempts:1});
    const user=await connected();await user.click(screen.getByRole('button',{name:'Sync Gmail now'}));
    expect(await screen.findByRole('status')).toHaveTextContent('Sync baseline established. No historical messages were imported.');
  });
  it('rejects unsafe sync response shapes and never renders raw provider errors',async()=>{
    sync=async()=>response({error:'PRIVATE_PROVIDER_DETAIL'},503);
    const user=await connected();await user.click(screen.getByRole('button',{name:'Sync Gmail now'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(screen.queryByText('PRIVATE_PROVIDER_DETAIL')).not.toBeInTheDocument();
    sync=async()=>response({status:'SYNCHRONIZED',inserted:-1,updated:0,removed:0,replayed:false,full_resynchronization:false,gmail_rest_attempts:1});
    await user.click(screen.getByRole('button',{name:'Sync Gmail now'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('Unexpected synchronization response');
    sync=async()=>response({status:'SYNCHRONIZED',inserted:3,updated:2,removed:1,replayed:false,full_resynchronization:false,gmail_rest_attempts:4});
    await user.click(screen.getByRole('button',{name:'Sync Gmail now'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('Unexpected synchronization response');
    sync=async()=>response({status:'SYNCHRONIZED',inserted:0,updated:0,removed:0,replayed:false,full_resynchronization:false,gmail_rest_attempts:13});
    await user.click(screen.getByRole('button',{name:'Sync Gmail now'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('Unexpected synchronization response');
  });
  it('handles failed searches and malformed citation metadata without rendering private details',async()=>{
    gmailSearch=async()=>response({error:'PRIVATE_SEARCH_DETAIL'},503);
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'interview');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(screen.queryByText('PRIVATE_SEARCH_DETAIL')).not.toBeInTheDocument();
    qa=async()=>response({...answer,evidence:[{...citation,title:{unexpected:'value'}}]});
    await ask(user);await waitFor(()=>expect(screen.getAllByRole('alert')).toHaveLength(1));
    expect(screen.getByRole('alert')).toHaveTextContent('Unexpected answer format');
  });
  it('offers the viewer only for current direct Gmail evidence with a valid opaque handle',async()=>{
    const user=await connected();
    expect(screen.queryByRole('button',{name:'View full email'})).not.toBeInTheDocument();
    qa=async()=>response({...directAnswer(null),gmail_retrieval:directAnswer().gmail_retrieval});
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user);
    expect(screen.queryByRole('button',{name:'View full email'})).not.toBeInTheDocument();
    qa=async()=>response({...directAnswer('bad-handle'),gmail_retrieval:directAnswer().gmail_retrieval});
    await user.clear(screen.getByLabelText('Your question'));await ask(user,'Show Gmail records about quotation');
    expect(screen.queryByRole('button',{name:'View full email'})).not.toBeInTheDocument();
    qa=async()=>response({...directAnswer(),evidence:[{...directAnswer().evidence[0],source_type:'DRIVE'}]});
    await user.clear(screen.getByLabelText('Your question'));await ask(user,'Show Gmail records about quotation');
    expect(screen.queryByRole('button',{name:'View full email'})).not.toBeInTheDocument();
    qa=async()=>response(directAnswer());
    await user.clear(screen.getByLabelText('Your question'));await ask(user,'Show Gmail records about quotation');
    expect(await screen.findByRole('button',{name:'View full email'})).toBeEnabled();
  });
  it('cancelling full-email confirmation makes no approval or view request',async()=>{
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    await screen.findByRole('button',{name:'View full email'});
    confirm.mockReturnValue(false);confirm.mockClear();
    await user.click(screen.getByRole('button',{name:'View full email'}));
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls.filter(([path])=>path.startsWith('/api/gmail/messages/'))).toHaveLength(0);
  });
  it('requests fresh approval then one message view with the same bearer and session scope',async()=>{
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    const calls=fetch.mock.calls.filter(([path])=>path.startsWith('/api/gmail/messages/'));
    expect(calls.map(([path])=>path)).toEqual(['/api/gmail/messages/view-approval','/api/gmail/messages/view']);
    expect(JSON.parse(calls[0][1].body)).toEqual({selection_id:VIEW_HANDLE});
    expect(JSON.parse(calls[1][1].body)).toEqual({approval_token:APPROVAL_TOKEN});
    expect(calls[0][1].headers.Authorization).toBe('Bearer '+TOKEN);
    expect(calls[0][1].headers['X-Client-Session']).toMatch(/^[0-9a-f-]{36}$/i);
    expect(calls[1][1].headers['X-Client-Session']).toBe(calls[0][1].headers['X-Client-Session']);
    expect(calls.every(([,options])=>options.cache==='no-store'&&options.credentials==='omit')).toBe(true);
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
  it('blocks repeated clicks while approval is pending',async()=>{
    let finishApproval;approveView=()=>new Promise(resolve=>{finishApproval=resolve;});
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const button=await screen.findByRole('button',{name:'View full email'});confirm.mockClear();
    fireEvent.click(button);fireEvent.click(button);
    expect(confirm).toHaveBeenCalledTimes(1);
    await waitFor(()=>expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1));
    await act(async()=>finishApproval(response({approval_token:APPROVAL_TOKEN})));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(1);
  });
  it('shows transient email headers, body and attachment display names',async()=>{
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    const dialog=await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading',{name:'Synthetic full email'})).toBeInTheDocument();
    expect(within(dialog).getByText('sender@example.test')).toBeInTheDocument();
    expect(within(dialog).getByText('reader@example.test')).toBeInTheDocument();
    expect(within(dialog).getByText(/19 Oct 2026/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Synthetic full body/)).toBeInTheDocument();
    expect(within(dialog).getByText('synthetic.pdf')).toBeInTheDocument();
    expect(within(dialog).queryByText(/attachment content/i)).not.toBeInTheDocument();
  });
  it('renders HTML-like email body as inert plain text',async()=>{
    viewEmail=async()=>response({...emailDetail,body_text:'<img src=x onerror=alert(1)>\n<script>alert(2)</script>'});
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    const dialog=await screen.findByRole('dialog');
    expect(dialog.querySelector('.gmail-body').textContent).toBe('<img src=x onerror=alert(1)>\n<script>alert(2)</script>');
    expect(dialog.querySelector('img,script')).toBeNull();
  });
  it('warns about unavailable or incomplete bodies and rejects oversized responses safely',async()=>{
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    viewEmail=async()=>response({...emailDetail,body_text:'',body_available:false,incomplete:true});
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    expect(await screen.findByRole('dialog')).toHaveTextContent('No readable message body was available');
    await user.click(screen.getByRole('button',{name:'Close'}));
    viewEmail=async()=>response({...emailDetail,body_text:'x'.repeat(262145)});
    await user.clear(screen.getByLabelText('Your question'));await ask(user,'Show Gmail records about quotation');
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('incomplete or unsupported');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('discloses a readable but incomplete or truncated body',async()=>{
    viewEmail=async()=>response({...emailDetail,incomplete:true,body_truncated:true});
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    const dialog=await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Some email details are unavailable or incomplete');
    expect(dialog).toHaveTextContent('Synthetic full body');
  });
  it('does not retry failed approval or detail requests and shows only safe errors',async()=>{
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    approveView=async()=>response({error:'PRIVATE_PROVIDER_BODY'},503);
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    expect(await within(card).findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(screen.queryByText('PRIVATE_PROVIDER_BODY')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(0);
  });
  it('does not retry a failed one-use detail read',async()=>{
    viewEmail=async()=>response({error:'PRIVATE_PROVIDER_BODY'},503);
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    expect(await within(card).findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(1);
    expect(screen.queryByText('PRIVATE_PROVIDER_BODY')).not.toBeInTheDocument();
  });
  it('shows a safe timeout beside the selected email',async()=>{
    viewEmail=async()=>response({error:'The Gmail request timed out before completion. No result was returned.',error_category:'GMAIL_REQUEST_TIMEOUT',upstream_status:503,upstream_reason:'UNSPECIFIED'},503);
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent('timed out');
    expect(screen.queryByText('PRIVATE_TIMEOUT_DETAIL')).not.toBeInTheDocument();
  });
  it.each([
    ['approval','GMAIL_REQUEST_TIMEOUT',503,{upstream_status:503,upstream_reason:'UNSPECIFIED'},'timed out'],
    ['detail','GMAIL_VIEWER_CAPACITY_EXHAUSTED',503,{upstream_status:503,upstream_reason:'UNSPECIFIED'},'viewer is busy'],
    ['approval','GMAIL_UNEXPECTED_RETRIEVAL_FAILURE',502,{upstream_status:404,upstream_reason:'UNSPECIFIED'},'selection or approval is no longer valid'],
    ['detail','GMAIL_UNEXPECTED_RETRIEVAL_FAILURE',503,{upstream_status:503,upstream_reason:'UNSPECIFIED'},'temporarily busy'],
    ['detail','GMAIL_REQUEST_BUDGET_EXHAUSTED',503,{upstream_status:503,upstream_reason:'UNSPECIFIED'},'safe request limit'],
    ['detail','GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED',503,{upstream_status:503,upstream_reason:'UNSPECIFIED'},'safe response-size limit'],
    ['detail','GMAIL_PROVIDER_AUTH_FAILURE',401,{upstream_status:401,upstream_reason:'UNSPECIFIED'},'did not accept the current authorization'],
    ['detail','GMAIL_PROVIDER_PERMISSION_FAILURE',502,{upstream_status:403,upstream_reason:'INSUFFICIENT_SCOPE'},'did not authorize this message read'],
    ['detail','GMAIL_PROVIDER_RATE_LIMIT',502,{upstream_status:429,upstream_reason:'RATE_LIMIT'},'temporarily limiting message reads'],
    ['detail','GMAIL_PROVIDER_PERMISSION_FAILURE',502,{upstream_status:403,upstream_reason:'QUOTA_EXCEEDED'},'request quota is temporarily unavailable'],
    ['detail','GMAIL_PROVIDER_UNAVAILABLE',503,{upstream_status:503,upstream_reason:'UNSPECIFIED'},'Gmail is temporarily unavailable'],
    ['detail','GMAIL_NETWORK_FAILURE',503,{upstream_status:503,upstream_reason:'UNSPECIFIED'},'Gmail is temporarily unavailable'],
    ['detail','GMAIL_PROVIDER_REQUEST_FAILURE',502,{upstream_status:502,upstream_reason:'UNSPECIFIED'},'could not complete this message read'],
  ])('maps trusted viewer errors safely (%s from %s)',async(stage,category,status,extra,expected)=>{
    const privateText='PRIVATE_PROVIDER_RESPONSE_BODY';
    const backendErrors={
      GMAIL_REQUEST_TIMEOUT:'The Gmail request timed out before completion. No result was returned.',
      GMAIL_VIEWER_CAPACITY_EXHAUSTED:'The Gmail viewer is busy; try again later.',
      GMAIL_UNEXPECTED_RETRIEVAL_FAILURE:status===502?'Gmail message selection is invalid or expired':'Gmail message selection capacity is unavailable',
      GMAIL_REQUEST_BUDGET_EXHAUSTED:'Gmail question retrieval attempt budget exhausted',
      GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED:'Gmail question retrieval exceeded its response-size limit',
      GMAIL_PROVIDER_AUTH_FAILURE:'Gmail message retrieval failed',
      GMAIL_PROVIDER_PERMISSION_FAILURE:'Gmail message retrieval failed',
      GMAIL_PROVIDER_RATE_LIMIT:'Gmail message retrieval failed',
      GMAIL_PROVIDER_UNAVAILABLE:'Gmail message retrieval failed',
      GMAIL_NETWORK_FAILURE:'Gmail request failed',
      GMAIL_PROVIDER_REQUEST_FAILURE:'Gmail message retrieval failed',
    };
    const failure=()=>response({error:backendErrors[category],error_category:category,...extra},status);
    if(stage==='approval') approveView=failure; else viewEmail=failure;
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent(expected);
    expect(screen.queryByText(privateText)).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(stage==='detail'?1:0);
  });
  it.each([
    ['unknown category',async()=>response({error:'PRIVATE_PROVIDER_TEXT',error_category:'GMAIL_PRIVATE_INTERNAL_ERROR'},503)],
    ['unknown category with otherwise recognized status',async()=>response({error:'PRIVATE_PROVIDER_TEXT',error_category:'GMAIL_PRIVATE_INTERNAL_ERROR'},403)],
    ['unknown category with viewer-specific status',async()=>response({error:'PRIVATE_PROVIDER_TEXT',error_category:'GMAIL_PRIVATE_INTERNAL_ERROR'},400)],
    ['malformed body',async()=>({ok:false,status:502,json:async()=>{throw new SyntaxError('private parse detail');}})],
  ])('uses the generic safe viewer fallback for %s',async(_case,failedRequest)=>{
    viewEmail=failedRequest;
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(screen.queryByText('PRIVATE_PROVIDER_TEXT')).not.toBeInTheDocument();
    expect(screen.queryByText('private parse detail')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(1);
  });
  it.each([
    [400,{error:'Invalid request or configuration'},'viewer request could not be validated'],
    [403,{error:'Sandbox access denied'},'current Gmail access policy'],
    [413,{error:'PRIVATE_BACKEND_DETAILS'},'backend could not complete'],
  ])('handles valid bare viewer errors safely for HTTP %s',async(status,payload,expected)=>{
    viewEmail=async()=>response(payload,status);
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent(expected);
    expect(screen.queryByText('PRIVATE_BACKEND_DETAILS')).not.toBeInTheDocument();
  });
  it.each(['approval','detail'])('accepts the exact backend structured status-only validation response for %s',async(stage)=>{
    const failure=async()=>response({error:'Invalid Gmail message viewer request',upstream_status:400,upstream_reason:'UNSPECIFIED'},400);
    if(stage==='approval') approveView=failure; else viewEmail=failure;
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent('viewer request could not be validated');
    expect(screen.queryByText('Invalid Gmail message viewer request')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(stage==='detail'?1:0);
  });
  it.each([
    ['missing error field',{error_category:'GMAIL_REQUEST_TIMEOUT',upstream_status:503,upstream_reason:'UNSPECIFIED'},503],
    ['malformed error field',{error:{detail:'PRIVATE'},error_category:'GMAIL_REQUEST_TIMEOUT',upstream_status:503,upstream_reason:'UNSPECIFIED'},503],
    ['empty error field',{error:'  ',error_category:'GMAIL_REQUEST_TIMEOUT',upstream_status:503,upstream_reason:'UNSPECIFIED'},503],
    ['error contradicts category',{error:'Gmail message selection is invalid or expired',error_category:'GMAIL_REQUEST_TIMEOUT',upstream_status:503,upstream_reason:'UNSPECIFIED'},503],
  ])('uses the generic fallback for a categorized response with %s',async(_case,payload,status)=>{
    viewEmail=async()=>response(payload,status);
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(screen.queryByText('PRIVATE')).not.toBeInTheDocument();
    expect(screen.queryByText(/timed out/)).not.toBeInTheDocument();
  });
  it('does not request viewer detail when the approval error contract is incomplete',async()=>{
    approveView=async()=>response({error_category:'GMAIL_REQUEST_TIMEOUT',upstream_status:503,upstream_reason:'UNSPECIFIED'},503);
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(0);
  });
  it.each([
    [400,{error:'Invalid request or configuration',upstream_status:403,upstream_reason:'UNSPECIFIED'},'contradictory upstream metadata'],
    [403,{error:'Sandbox access denied',upstream_status:403},'partial metadata'],
    [413,{error:'Gmail message viewer request exceeds the safe size limit',upstream_status:400,upstream_reason:'UNSPECIFIED'},'contradictory metadata'],
    [400,{error:'Unexpected backend validation detail',upstream_status:400,upstream_reason:'UNSPECIFIED'},'unsupported structured validation text'],
    [400,{upstream_status:400,upstream_reason:'UNSPECIFIED'},'missing structured error'],
    [400,{error:{detail:'PRIVATE'},upstream_status:400,upstream_reason:'UNSPECIFIED'},'malformed structured error'],
    [400,{error:'Invalid Gmail message viewer request',upstream_status:400},'missing structured reason'],
    [400,{error:'Invalid Gmail message viewer request',upstream_status:403,upstream_reason:'UNSPECIFIED'},'contradictory structured status'],
    [413,{error:'Request size exceeded',upstream_status:413,upstream_reason:'UNSPECIFIED'},'unsupported but internally consistent HTTP 413'],
    [413,{error:'Request size exceeded'},'unsupported bare HTTP 413'],
    [400,{error:'  ',upstream_status:400,upstream_reason:'UNSPECIFIED'},'malformed error field'],
  ])('uses the generic fallback for HTTP %s with %s',async(status,payload,_case)=>{
    viewEmail=async()=>response(payload,status);
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent('backend could not complete');
    if(typeof payload.error==='string') expect(screen.queryByText(payload.error)).not.toBeInTheDocument();
  });
  it.each([
    [502,'GMAIL_REQUEST_TIMEOUT',{upstream_status:503,upstream_reason:'UNSPECIFIED'}],
    [503,'GMAIL_UNEXPECTED_RETRIEVAL_FAILURE',{upstream_status:404,upstream_reason:'UNSPECIFIED'}],
    [502,'GMAIL_PROVIDER_AUTH_FAILURE',{upstream_status:401,upstream_reason:'UNSPECIFIED'}],
    [503,'GMAIL_PROVIDER_PERMISSION_FAILURE',{upstream_status:403,upstream_reason:'INSUFFICIENT_SCOPE'}],
    [502,'GMAIL_NETWORK_FAILURE',{upstream_status:503,upstream_reason:'UNSPECIFIED'}],
  ])('uses the generic safe fallback for contradictory viewer response %s %s',async(status,category,extra)=>{
    viewEmail=async()=>response({error:'PRIVATE_BACKEND_DETAILS',error_category:category,...extra},status);
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(screen.queryByText('PRIVATE_BACKEND_DETAILS')).not.toBeInTheDocument();
    expect(screen.queryByText(/Gmail did not accept|timed out|temporarily unavailable|no longer valid/)).not.toBeInTheDocument();
  });
  it('preserves the local session for the complete provider-auth error contract',async()=>{
    viewEmail=async()=>response({error:'Gmail message retrieval failed',error_category:'GMAIL_PROVIDER_AUTH_FAILURE',
      upstream_status:401,upstream_reason:'UNSPECIFIED'},401);
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toHaveTextContent('Gmail did not accept the current authorization');
    expect(screen.queryByLabelText('Local access token')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(1);
  });
  it.each([
    [{error_category:'GMAIL_PROVIDER_AUTH_FAILURE',upstream_status:401,upstream_reason:'UNSPECIFIED'},'missing error field'],
    [{error:'Gmail message retrieval failed',error_category:'GMAIL_PROVIDER_AUTH_FAILURE'},'category without upstream status'],
    [{error:'PRIVATE_BACKEND_DETAILS',error_category:'GMAIL_PROVIDER_AUTH_FAILURE',upstream_status:502,upstream_reason:'UNSPECIFIED'},'contradictory upstream status'],
    [{error:'PRIVATE_BACKEND_DETAILS',error_category:'GMAIL_PROVIDER_AUTH_FAILURE',upstream_status:401,upstream_reason:'private detail'},'unrecognized upstream reason'],
  ])('clears the local session for a 401 provider category with %s',async(payload)=>{
    viewEmail=async()=>response(payload,401);
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    await screen.findByLabelText('Local access token');
    expect(screen.queryByText('Synthetic quotation')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('backend could not complete');
    expect(screen.queryByText('PRIVATE_BACKEND_DETAILS')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(1);
  });
  it('places an approval error on the selected card among 20 Gmail results only',async()=>{
    const rows=Array.from({length:20},(_,index)=>({...gmailRow,subject:`Synthetic result ${index+1}`,
      selection_id:String(index+1).padStart(43,'0')}));
    gmailSearch=async()=>response({results:rows,partial_results:false,coverage_warning:null});
    approveView=async()=>response({error:'PRIVATE_APPROVAL_DETAIL'},503);
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'Google');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    const selected=screen.getByRole('heading',{name:'Synthetic result 20'}).closest('.gmail-search-result');
    const other=screen.getByRole('heading',{name:'Synthetic result 1'}).closest('.gmail-search-result');
    await user.click(within(selected).getByRole('button',{name:'View full email'}));
    expect(await within(selected).findByRole('alert')).toHaveTextContent('backend could not complete');
    expect(within(other).queryByRole('alert')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(0);
  });
  it('moves viewer errors to a newly selected email and clears them on disconnect',async()=>{
    const rows=[{...gmailRow,subject:'Synthetic first email',selection_id:'1'.repeat(43)},
      {...gmailRow,subject:'Synthetic second email',selection_id:'2'.repeat(43)}];
    gmailSearch=async()=>response({results:rows,partial_results:false,coverage_warning:null});
    approveView=async()=>response({error:'PRIVATE_APPROVAL_DETAIL'},503);
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'Google');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    const first=screen.getByRole('heading',{name:'Synthetic first email'}).closest('.gmail-search-result');
    const second=screen.getByRole('heading',{name:'Synthetic second email'}).closest('.gmail-search-result');
    await user.click(within(first).getByRole('button',{name:'View full email'}));
    expect(await within(first).findByRole('alert')).toBeInTheDocument();
    expect(within(second).queryByRole('alert')).not.toBeInTheDocument();
    await user.click(within(second).getByRole('button',{name:'View full email'}));
    expect(await within(second).findByRole('alert')).toBeInTheDocument();
    expect(within(first).queryByRole('alert')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button',{name:'Disconnect'}));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('clears viewer errors when a new question begins',async()=>{
    const user=await connected();qa=async()=>response(directAnswer());approveView=async()=>response({error:'PRIVATE',},503);
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const card=screen.getByText('Synthetic quotation').closest('.citation');
    await user.click(within(card).getByRole('button',{name:'View full email'}));
    expect(await within(card).findByRole('alert')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Your question'));await ask(user,'Show Gmail records about interview');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('does not restore a late viewer success after a new Gmail search begins',async()=>{
    let finishView;viewEmail=()=>new Promise(resolve=>{finishView=resolve;});
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    await waitFor(()=>expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(1));

    await user.type(screen.getByLabelText('Keyword'),'Google');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await screen.findByRole('heading',{name:'Synthetic interview'});
    await act(async()=>finishView(response(emailDetail)));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('Synthetic full body')).not.toBeInTheDocument();
  });
  it('does not restore a late viewer success after a new question begins',async()=>{
    let finishView;viewEmail=()=>new Promise(resolve=>{finishView=resolve;});
    gmailSearch=async()=>response(gmailResults);
    const user=await connected();await user.type(screen.getByLabelText('Keyword'),'Google');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    await waitFor(()=>expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view')).toHaveLength(1));

    await ask(user,'Show quotation records from Gmail');
    await screen.findByRole('heading',{name:'Recorded evidence'});
    await act(async()=>finishView(response(emailDetail)));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('Synthetic full body')).not.toBeInTheDocument();
  });
  it('does not restore a late viewer error after its request is canceled',async()=>{
    let finishApproval;approveView=()=>new Promise(resolve=>{finishApproval=resolve;});
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    await user.click(await screen.findByRole('button',{name:'View full email'}));
    await waitFor(()=>expect(fetch.mock.calls.filter(([path])=>path==='/api/gmail/messages/view-approval')).toHaveLength(1));

    await user.type(screen.getByLabelText('Keyword'),'Google');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    await screen.findByRole('heading',{name:'Synthetic interview'});
    await act(async()=>finishApproval(response({error:'PRIVATE_PROVIDER_DETAIL'},503)));

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('PRIVATE_PROVIDER_DETAIL')).not.toBeInTheDocument();
  });
  it('closing clears full email data and returns focus to its source card',async()=>{
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const trigger=await screen.findByRole('button',{name:'View full email'});const sourceCard=trigger.closest('.citation');await user.click(trigger);
    expect(await screen.findByRole('dialog')).toHaveTextContent('Synthetic full body');
    const close=screen.getByRole('button',{name:'Close'});
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal','true');
    fireEvent.keyDown(document,{key:'Tab'});expect(close).toHaveFocus();
    fireEvent.keyDown(document,{key:'Escape'});
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('Synthetic full body')).not.toBeInTheDocument();
    expect(sourceCard).toHaveFocus();
    expect(screen.queryByRole('button',{name:'View full email'})).not.toBeInTheDocument();
  });
  it('clears sensitive detail when Gmail search filters change and on disconnect',async()=>{
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    await user.click(await screen.findByRole('button',{name:'View full email'}));await screen.findByRole('dialog');
    await user.type(screen.getByLabelText('Keyword'),'quotation');
    await user.click(screen.getByRole('button',{name:'Search Gmail'}));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button',{name:'Disconnect'}));
    expect(screen.queryByText('Synthetic full body')).not.toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'View full email'})).not.toBeInTheDocument();
  });
  it('changes the in-memory browser session after disconnecting and reconnecting',async()=>{
    const user=await connected();qa=async()=>response(directAnswer());
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const first=fetch.mock.calls.find(([path])=>path==='/api/qa/ask')[1].headers['X-Client-Session'];
    await user.click(screen.getByRole('button',{name:'Disconnect'}));
    await user.type(screen.getByLabelText('Local access token'),TOKEN);
    await user.click(screen.getByRole('button',{name:'Connect',exact:true}));
    await screen.findByText('Local session connected');
    await user.click(screen.getByRole('checkbox',{name:/Search Gmail now/}));await ask(user,'Show Gmail records about quotation');
    const asks=fetch.mock.calls.filter(([path])=>path==='/api/qa/ask');
    expect(asks).toHaveLength(2);
    expect(asks[1][1].headers['X-Client-Session']).not.toBe(first);
  });
});
