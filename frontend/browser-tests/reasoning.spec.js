import { test, expect } from '@playwright/test';
import fs from 'node:fs';
const tokenFile=process.env.FRONTEND_REASONING_TOKEN_FILE;
test.skip(!tokenFile,'Set FRONTEND_REASONING_TOKEN_FILE for the separate M3 synthetic runtime.');
test('three bounded PDF question types render exact cited evidence on desktop and mobile',async({page})=>{
 const token=fs.readFileSync(tokenFile,'utf8').trim();const logs=[];page.on('console',m=>logs.push(m.text()));
 await page.goto('/');await page.getByLabel('Local access token').fill(token);await page.getByRole('button',{name:'Connect',exact:true}).click();await expect(page.getByText('Local session connected')).toBeVisible();
 const cases=[['What were our sales last month?','USD 1500'],['Which sales emails are still pending a reply?','pending-boundary'],['What did the site team agree on in the WhatsApp group this week?','sim-agree-1'],['What did the site team decide this week?','sim-agree-1']];
 for(const [q,expected] of cases){await page.getByLabel('Your question').fill(q);const wait=page.waitForResponse(r=>r.url().endsWith('/api/qa/ask'));await page.getByRole('button',{name:'Ask question'}).click();const response=await wait;expect(response.status()).toBe(200);const a=await response.json();expect(a.status).toBe('EVIDENCE_FOUND');expect(a.evidence.length).toBe(2);expect(JSON.stringify(a)).toContain(expected);
  const lookup=await page.request.get('/api/unified/search?limit=100',{headers:{Authorization:'Bearer '+token}});const rows=await lookup.json();for(const e of a.evidence){const r=rows.find(x=>x.source.document_id===e.document_id);expect(r.source.content_text.slice(e.start_offset,e.end_offset)).toBe(e.excerpt);expect(r.source.source_version).toBe(e.source_version);await expect(page.locator('blockquote').filter({hasText:e.excerpt})).toBeVisible();}
 }
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 await page.getByLabel('Your question').fill('What is our profit margin?');await page.getByRole('button',{name:'Ask question'}).click();await expect(page.getByRole('heading',{name:'Not enough evidence'})).toBeVisible();
 expect(logs.some(x=>x.includes(token)||x.includes('M3_EXCLUDED_CANARY'))).toBe(false);
 await page.getByRole('button',{name:'Disconnect',exact:true}).click();expect(await page.evaluate(()=>localStorage.length+sessionStorage.length)).toBe(0);
});
