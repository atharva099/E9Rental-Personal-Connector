import { test, expect } from '@playwright/test';
import fs from 'node:fs';
const tokenFile=process.env.FRONTEND_SMOKE_TOKEN_FILE;
test.skip(!tokenFile,'Set FRONTEND_SMOKE_TOKEN_FILE to the isolated external synthetic runtime token path.');
test('real authenticated proxy flow, citations, filtering, errors, no evidence and mobile',async({page})=>{
  const token=fs.readFileSync(tokenFile,'utf8').trim(); // Never print, persist in browser storage, or include in traces.
  const errors=[];const consoleMessages=[];
  page.on('pageerror',()=>errors.push('browser-error'));
  page.on('console',message=>consoleMessages.push(message.text()));
  await page.goto('/');
  await expect(page.getByText('Backend online')).toBeVisible();
  await expect(page.getByRole('button',{name:'Ask question'})).toBeDisabled();
  // Wrong-token negative check exercises the real bearer guard through Vite.
  await page.getByLabel('Local access token').fill('synthetic-wrong-token-for-auth-negative-test');
  await page.getByRole('button',{name:'Connect',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('Access token rejected');
  await page.getByLabel('Local access token').fill(token);
  await page.getByRole('button',{name:'Connect',exact:true}).click();
  await expect(page.getByText('Local session connected')).toBeVisible();
  await page.getByLabel('Your question').fill('Show quotation records across Gmail and Drive');
  const qaResponse=page.waitForResponse(r=>r.url().endsWith('/api/qa/ask') && r.request().method()==='POST');
  await page.getByRole('button',{name:'Ask question'}).click();
  const response=await qaResponse;
  expect(response.status()).toBe(200);
  expect(response.request().headers().authorization===('Bearer '+token)).toBe(true);
  const payload=await response.json();
  expect(payload.evidence.length).toBe(2);
  for(const e of payload.evidence){
    await expect(page.getByText(e.reference+' · '+(e.source_type==='GMAIL'?'Gmail':'Google Drive'),{exact:true})).toBeVisible();
    await expect(page.locator('blockquote').filter({hasText:e.excerpt})).toBeVisible();
    await expect(page.getByText(e.locator,{exact:true})).toBeVisible();
  }
  await expect(page.getByText('SYNTHETIC · practice data').first()).toBeVisible();
  // Regression: the packaged demo must contain the invoice from the documented Q&A example.
  await page.getByLabel('Your question').fill('What is the rental invoice amount in Gmail?');
  const invoiceRequest=page.waitForResponse(r=>r.url().endsWith('/api/qa/ask') && r.request().method()==='POST');
  await page.getByRole('button',{name:'Ask question'}).click();
  const invoiceResponse=await invoiceRequest;expect(invoiceResponse.status()).toBe(200);
  const invoice=await invoiceResponse.json();
  expect(invoice.status).toBe('EVIDENCE_FOUND');expect(invoice.evidence.length).toBe(1);
  const evidence=invoice.evidence[0];
  expect(evidence.source_type).toBe('GMAIL');expect(evidence.source_id).toBe('demo-invoice-1200');
  expect(evidence.data_mode).toBe('SYNTHETIC');expect(evidence.excerpt).toBe('Rental invoice amount is USD 1200.');
  const lookup=await page.request.get('/api/unified/search?source=GMAIL&keyword=invoice&limit=100',
    {headers:{Authorization:'Bearer '+token}});
  expect(lookup.status()).toBe(200);
  const stored=(await lookup.json()).find(r=>r.source.external_id===evidence.source_id);
  expect(stored.source.document_id).toBe(evidence.document_id);expect(stored.source.source_version).toBe(evidence.source_version);
  expect(stored.citation.locator).toBe(evidence.locator);
  expect(stored.source.content_text.slice(evidence.start_offset,evidence.end_offset)).toBe(evidence.excerpt);
  await expect(page.locator('blockquote').filter({hasText:'Rental invoice amount is USD 1200.'})).toBeVisible();
  await expect(page.getByText('demo-invoice-1200',{exact:true})).toBeVisible();
  await page.evaluate(()=>window.scrollTo(0,0));
  await page.screenshot({path:'../target/frontend-desktop.png',fullPage:true});
  await page.getByLabel('Keyword',{exact:true}).fill('quotation');
  await page.getByLabel('Source',{exact:true}).selectOption('DRIVE');
  await page.getByRole('button',{name:'Search records',exact:true}).click();
  await expect(page.getByText('1 records shown · not a total source count')).toBeVisible();
  await page.getByLabel('Your question').fill('Who is the CEO of Neptune?');
  await page.getByRole('button',{name:'Ask question'}).click();
  await expect(page.getByRole('heading',{name:'Not enough evidence'})).toBeVisible();
  // Real invalid-question 400 using a valid-length field: the backend forbids control characters.
  await page.getByLabel('Your question').fill('Show\nrecords');
  await page.getByRole('button',{name:'Ask question'}).click();
  await expect(page.getByRole('alert')).toContainText('Check your question');
  await page.getByLabel('Your question').fill('What commitments were made in WhatsApp?');
  await page.getByRole('button',{name:'Ask question'}).click();
  await expect(page.getByText('SIMULATED · no live WhatsApp').first()).toBeVisible();
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await page.evaluate(()=>window.scrollTo(0,0));
  await page.screenshot({path:'../target/frontend-mobile.png',fullPage:true});
  await page.getByRole('button',{name:'Disconnect',exact:true}).click();
  await expect(page.getByLabel('Local access token')).toBeVisible();
  expect(await page.evaluate(()=>localStorage.length===0 && sessionStorage.length===0)).toBe(true);
  expect(errors.length).toBe(0);
  expect(consoleMessages.some(m=>m.includes(token) || m.includes('Please share pricing') || m.includes('Who is the CEO'))).toBe(false);
});
