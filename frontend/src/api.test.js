import { afterEach, expect, it, vi } from 'vitest';
import { request, validAnswer, validCapabilities } from './api.js';
afterEach(()=>{vi.unstubAllGlobals();vi.useRealTimers();});
it('redacts network failures and invalid JSON without exposing server details',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockRejectedValue(new Error('PRIVATE_BACKEND_DETAIL')));
  await expect(request('/api/qa/ask','synthetic')).rejects.toThrow('backend is unavailable');
  fetch.mockResolvedValue({ok:true,json:async()=>{throw new Error('PRIVATE_JSON');}});
  await expect(request('/api/qa/ask','synthetic')).rejects.toThrow('unreadable response');
});
it('times out a stalled request',async()=>{
  vi.useFakeTimers();
  vi.stubGlobal('fetch',(_path,{signal})=>new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')))));
  const operation=expect(request('/health','')).rejects.toThrow('timed out');
  await vi.advanceTimersByTimeAsync(15001);await operation;
});
it('accepts only exact, supported backend capability responses',()=>{
  expect(validCapabilities({mode:'PERSONAL'})).toBe(true);
  expect(validCapabilities({mode:'SANDBOX'})).toBe(true);
  expect(validCapabilities({mode:'UNKNOWN'})).toBe(false);
  expect(validCapabilities({mode:'PERSONAL',gmailAccount:'private@example.test'})).toBe(false);
  expect(validCapabilities({})).toBe(false);
  expect(validCapabilities(null)).toBe(false);
});
it('validates structured transient recent Gmail rows and rejects malformed row data',()=>{
  const recent={status:'EVIDENCE_FOUND',mode:'DETERMINISTIC_EXTRACTIVE',llm_generated:false,external_processing:false,
    answer:'Showing up to 10 recent Gmail messages. This is not a complete mailbox listing.',evidence:[],sources:['GMAIL'],
    limited_coverage:true,limitations:[],partial_results:false,
    gmail_retrieval:{gmail_rest_attempts:4,profile_requests:1,list_requests:1,message_requests:2,attachment_requests:0,
      oauth_refresh_attempts:0,response_bytes:512,messages_retrieved:2},
    recent_emails:[{subject:'Synthetic subject',sender:'sender@example.test',received_at:'2026-10-19T12:30:00Z',
      excerpt:'Synthetic preview',selection_id:'h'.repeat(43)}]};
  expect(validAnswer(recent)).toBe(true);
  expect(validAnswer({...recent,recent_emails:[{...recent.recent_emails[0],selection_id:'raw-gmail-id'}]})).toBe(false);
  expect(validAnswer({...recent,recent_emails:[{...recent.recent_emails[0],private_body:'unexpected'}]})).toBe(false);
  expect(validAnswer({...recent,recent_emails:[{...recent.recent_emails[0],excerpt:'x'.repeat(241)}]})).toBe(false);
  expect(validAnswer({...recent,gmail_retrieval:undefined})).toBe(false);
  expect(validAnswer({...recent,recent_emails:null})).toBe(false);
  expect(validAnswer({...recent,sources:['DRIVE']})).toBe(false);
});
